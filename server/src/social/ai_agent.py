# ai_agent.py - AI Agent using Doubao (Volcengine Ark) function calling
# Falls back to keyword matching when API key is not configured
import os
import json
import re
from typing import Optional, Dict, Any
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from .auth import get_current_user
from .maps import _amap_get

router = APIRouter(prefix="/api/agent", tags=["agent"])

ARK_API_KEY = os.getenv("ARK_API_KEY", "")
ARK_BASE_URL = os.getenv("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3")
ARK_MODEL = os.getenv("ARK_MODEL", "")

def _get_ark_key(request: Request = None) -> str:
    """Get ARK key: client header > server env > empty"""
    if request:
        client_key = request.headers.get("X-Visus-ARK-Key", "")
        if client_key:
            return client_key
    return ARK_API_KEY

AGENT_SYSTEM_PROMPT = """你是 Visus，一个为视障人士设计的AI助手。用户通过语音与你交流。
你的核心功能是理解用户的自然语言指令，将其映射为具体的功能调用。

你的名字叫 Visus，回复时用中文，语气温暖、简洁。

## 可用功能：
1. navigate - 导航到目的地。参数: destination(目的地名称), origin_lat, origin_lng
2. start_assist - 开启摄像头辅助出行
3. stop_assist - 停止摄像头
4. send_sos - 发送紧急求助给所有好友。参数: message(可选求助信息)
5. check_friends - 查看好友安全状态
6. send_location - 向好友发送当前位置。参数: friend_name(可选)
7. create_group - 创建群组。参数: name(群名), member_names(成员名称列表)
8. send_group_msg - 发送群消息。参数: group_name, content
9. read_messages - 朗读未读消息
10. where_am_i - 获取当前地址
11. search_nearby - 搜索周边。参数: keywords(如医院、超市、餐厅)
12. chat - 普通聊天，不调用功能

## 重要规则：
- 用户说"导航去X"、"去X"、"带我去X"、"怎么去X"→ 调用 navigate
- 用户说"救命"、"求助"、"帮帮我"、"我不行了" → 调用 send_sos
- 用户说"开启导航"、"开始走路"、"开摄像头"、"出门" → 调用 start_assist
- 用户说"看看我的好友"、"好友状态"、"朋友们怎么样" → 调用 check_friends
- 用户说"我在哪"、"我的位置" → 调用 where_am_i
- 用户说"附近有X吗"、"找X" → 调用 search_nearby
- 如果无法确定意图 → 调用 chat，友善地请用户再说一遍

## 输出格式（严格JSON）：
{"action": "函数名", "params": {"key": "value"}, "reply_text": "给用户的语音回复"}
"""


class AgentRequest(BaseModel):
    text: str
    user_id: int = 0
    lat: float = 0.0
    lng: float = 0.0
    city: str = ""


def _keyword_match(text: str) -> dict:
    """Fallback keyword-based intent matching when no API key."""
    t = text.lower().strip()

    # Emergency
    if any(w in t for w in ["救命", "求助", "帮帮我", "sos", "我不行", "救救我", "emergency"]):
        return {"action": "send_sos", "params": {"message": text}, "reply_text": "已向所有好友发送紧急求助，他们很快就会收到你的位置信息。请不要慌张，保持冷静。"}

    # Navigation
    nav_match = re.search(r"(?:去|导航到|带我去|到|前往)(.+)", t)
    if nav_match:
        dest = nav_match.group(1).strip().rstrip("。，.!！?？")
        return {"action": "navigate", "params": {"destination": dest},
                "reply_text": f"好的，正在为你规划前往{dest}的步行路线，请稍候。"}

    # Where am I
    if any(w in t for w in ["我在哪", "我的位置", "当前位置", "这是哪"]):
        return {"action": "where_am_i", "params": {}, "reply_text": "正在获取你的当前位置。"}

    # Start assist
    if any(w in t for w in ["开启导航", "开始走路", "开摄像头", "出门", "辅助出行", "帮我看看"]):
        return {"action": "start_assist", "params": {}, "reply_text": "好的，已开启摄像头辅助出行模式。我会实时为你播报前方的路况和障碍物。"}

    # Stop assist
    if any(w in t for w in ["停止", "关闭摄像头", "关闭导航", "结束"]):
        return {"action": "stop_assist", "params": {}, "reply_text": "已停止辅助出行。"}

    # Check friends
    if any(w in t for w in ["好友", "朋友", "联系人", "家人们"]):
        return {"action": "check_friends", "params": {}, "reply_text": "正在查看你的好友状态。"}

    # Send location
    if "发送位置" in t or "分享位置" in t or "告诉" in t:
        return {"action": "send_location", "params": {}, "reply_text": "正在向好友发送你的位置。"}

    # Search nearby
    nearby_match = re.search(r"(?:附近有|找|有没有|附近|周边)(.+)", t)
    if nearby_match:
        kw = nearby_match.group(1).strip().rstrip("。，.!！?？吗呢")
        return {"action": "search_nearby", "params": {"keywords": kw},
                "reply_text": f"正在为你搜索附近的{kw}。"}

    # Send group message
    group_msg_match = re.search(r"(?:给|向|在)(.+?)(?:发|发送|说)(.+)", t)
    if group_msg_match:
        return {"action": "send_group_msg", "params": {
            "group_name": group_msg_match.group(1).strip(),
            "content": group_msg_match.group(2).strip()
        }, "reply_text": "消息已发送。"}

    # Create group
    if "建群" in t or "创建群" in t:
        name_match = re.search(r"叫(.+?)的群|创建(.+?)群|建(.+?)群", t)
        gname = name_match.group(1) if name_match else "新群组"
        return {"action": "create_group", "params": {"name": gname},
                "reply_text": f"好的，正在为你创建群组「{gname}」。"}

    # Read messages
    if any(w in t for w in ["读消息", "有什么消息", "未读", "念一下"]):
        return {"action": "read_messages", "params": {}, "reply_text": "正在为你朗读未读消息。"}

    # Fallback
    return {"action": "chat", "params": {}, "reply_text": f"Visus收到。你说「{text}」，请问需要我帮你做什么呢？你可以说「去附近的医院」「查看好友」「发送求助」等。"}


async def _doubao_agent(text: str, user_id: int, lat: float, lng: float, city: str, ark_key: str = "") -> dict:
    """Call Doubao (Volcengine Ark) with function definitions for intent parsing."""
    import httpx
    key = ark_key or ARK_API_KEY

    functions = [
        {"type": "function", "function": {
            "name": "navigate", "description": "导航到指定目的地",
            "parameters": {"type": "object", "properties": {
                "destination": {"type": "string", "description": "目的地名称或地址"},
                "origin_lat": {"type": "number"}, "origin_lng": {"type": "number"}
            }, "required": ["destination"]}}},
        {"type": "function", "function": {
            "name": "send_sos", "description": "发送紧急求助给所有好友",
            "parameters": {"type": "object", "properties": {
                "message": {"type": "string", "description": "求助信息"}
            }}}},
        {"type": "function", "function": {
            "name": "start_assist", "description": "开启摄像头辅助出行",
            "parameters": {"type": "object", "properties": {}}}},
        {"type": "function", "function": {
            "name": "check_friends", "description": "查看好友安全状态",
            "parameters": {"type": "object", "properties": {}}}},
        {"type": "function", "function": {
            "name": "send_location", "description": "发送自己的位置给好友",
            "parameters": {"type": "object", "properties": {
                "friend_name": {"type": "string", "description": "好友名称，不填则发送给所有好友"}
            }}}},
        {"type": "function", "function": {
            "name": "create_group", "description": "创建群组",
            "parameters": {"type": "object", "properties": {
                "name": {"type": "string", "description": "群名称"},
                "member_names": {"type": "array", "items": {"type": "string"}, "description": "成员名称列表"}
            }, "required": ["name"]}}},
        {"type": "function", "function": {
            "name": "send_group_msg", "description": "发送群消息",
            "parameters": {"type": "object", "properties": {
                "group_name": {"type": "string"}, "content": {"type": "string"}
            }, "required": ["group_name", "content"]}}},
        {"type": "function", "function": {
            "name": "read_messages", "description": "朗读未读消息",
            "parameters": {"type": "object", "properties": {}}}},
        {"type": "function", "function": {
            "name": "where_am_i", "description": "获取当前地址",
            "parameters": {"type": "object", "properties": {}}}},
        {"type": "function", "function": {
            "name": "search_nearby", "description": "搜索周边设施",
            "parameters": {"type": "object", "properties": {
                "keywords": {"type": "string", "description": "搜索关键词，如医院、超市"}
            }, "required": ["keywords"]}}},
    ]

    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            f"{ARK_BASE_URL}/chat/completions",
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={
                "model": ARK_MODEL or "doubao-pro-32k",
                "messages": [
                    {"role": "system", "content": AGENT_SYSTEM_PROMPT},
                    {"role": "user", "content": f"用户位置: lat={lat}, lng={lng}, city={city}\n用户说: {text}"}
                ],
                "tools": functions,
                "tool_choice": "auto",
                "temperature": 0.1,
                "max_tokens": 500,
            }
        )
        data = resp.json()

    choice = (data.get("choices") or [{}])[0]
    message = choice.get("message", {})
    content = (message.get("content") or "").strip()

    # Check for function call
    tool_calls = message.get("tool_calls", [])
    if tool_calls:
        tc = tool_calls[0]
        func_name = tc["function"]["name"]
        try:
            params = json.loads(tc["function"]["arguments"])
        except Exception:
            params = {}
        # Also try to extract reply_text from content if it's JSON
        reply = content or f"好的，正在执行{func_name}。"
        try:
            parsed = json.loads(content)
            reply = parsed.get("reply_text", reply)
        except Exception:
            pass
        return {"action": func_name, "params": params, "reply_text": reply}

    # No function call — try to parse the model's JSON response
    try:
        parsed = json.loads(content)
        return {
            "action": parsed.get("action", "chat"),
            "params": parsed.get("params", {}),
            "reply_text": parsed.get("reply_text", content),
        }
    except Exception:
        pass

    return {"action": "chat", "params": {},
            "reply_text": content or f"Visus收到：{text}"}


@router.post("/command")
async def agent_command(body: AgentRequest, request: Request):
    """Main AI Agent endpoint. Receives user text, returns action + reply."""
    user = get_current_user(request)

    try:
        key = _get_ark_key(request)
        if key and key.strip():
            result = await _doubao_agent(body.text, user.id, body.lat, body.lng, body.city, key)
        else:
            result = _keyword_match(body.text)
    except Exception as e:
        # Fallback to keyword matching on any error
        result = _keyword_match(body.text)
        result["reply_text"] = result.get("reply_text", "") + " (AI服务暂不可用，使用本地理解)"

    # Pre-load extra data for certain actions
    extra = {}
    action = result.get("action", "chat")
    params = result.get("params", {})

    if action == "navigate" and params.get("destination"):
        try:
            dest = params["destination"]
            geo = await _amap_get("/geocode/geo", {"address": dest, "city": body.city or "上海"})
            geocodes = geo.get("geocodes", [])
            if geocodes:
                loc = geocodes[0].get("location")
                if loc and body.lat != 0:
                    route = await _amap_get("/direction/walking", {
                        "origin": f"{body.lng},{body.lat}",
                        "destination": loc,
                    })
                    path = (route.get("route", {}).get("paths") or [{}])[0]
                    extra = {
                        "destination_lat_lng": loc,
                        "distance": path.get("distance", 0),
                        "duration": path.get("duration", 0),
                    }
        except Exception:
            pass

    if action == "where_am_i" and body.lat != 0:
        try:
            rev = await _amap_get("/geocode/regeo", {
                "location": f"{body.lng},{body.lat}", "extensions": "base"
            })
            extra["address"] = (rev.get("regeocode") or {}).get("formatted_address", "")
        except Exception:
            pass

    if action == "search_nearby" and params.get("keywords"):
        try:
            kw = params["keywords"]
            sr = await _amap_get("/place/text", {
                "keywords": kw,
                "location": f"{body.lng},{body.lat}" if body.lat else "",
                "radius": "3000", "sortrule": "distance",
            })
            extra["results"] = [{
                "name": p.get("name"), "address": p.get("address"), "distance": p.get("distance", "")
            } for p in (sr.get("pois") or [])[:5]]
        except Exception:
            pass

    return {
        "success": True,
        "data": {
            "action": action,
            "params": params,
            "reply_text": result.get("reply_text", ""),
            "extra": extra,
        }
    }
