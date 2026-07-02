# maps.py - Amap (高德地图) Web API integration for navigation and location services
import os
import json
import httpx
from typing import Optional, List, Dict, Any
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from .auth import get_current_user

router = APIRouter(prefix="/api/maps", tags=["maps"])

AMAP_KEY = os.getenv("AMAP_API_KEY", "")
AMAP_BASE = "https://restapi.amap.com/v3"

# Client can pass their own API key via this header
def _get_amap_key(request: Request = None) -> str:
    """Get AMAP key: client header > server env > empty"""
    if request:
        client_key = request.headers.get("X-Visus-AMAP-Key", "")
        if client_key:
            return client_key
    return AMAP_KEY

# ── Pydantic models ──
class GeocodeRequest(BaseModel):
    address: str
    city: str = ""

class ReverseGeocodeRequest(BaseModel):
    latitude: float
    longitude: float

class RouteRequest(BaseModel):
    origin: str          # "lng,lat" or address
    destination: str     # "lng,lat" or address

class SearchRequest(BaseModel):
    keywords: str        # "医院","餐厅","超市"
    city: str = ""
    latitude: float = 0.0
    longitude: float = 0.0

class NavigateRequest(BaseModel):
    destination: str     # natural language: "最近的医院"
    origin: str = ""     # optional, defaults to current location
    current_lat: float = 0.0
    current_lng: float = 0.0
    city: str = ""


async def _amap_get(path: str, params: dict, request: Request = None) -> dict:
    """Call Amap Web API. Uses client key if provided, else server key."""
    key = _get_amap_key(request) if request else AMAP_KEY
    params["key"] = key
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(f"{AMAP_BASE}{path}", params=params)
        data = resp.json()
        if data.get("status") != "1":
            raise HTTPException(status_code=502, detail={
                "success": False,
                "error": f"Amap API error: {data.get('info', 'unknown')}"
            })
        return data


@router.post("/geocode")
async def geocode(body: GeocodeRequest):
    """Convert address to coordinates."""
    params = {"address": body.address}
    if body.city:
        params["city"] = body.city
    data = await _amap_get("/geocode/geo", params)
    geocodes = data.get("geocodes", [])
    if not geocodes:
        return {"success": True, "data": {"results": []}}
    results = [{
        "name": g.get("name"),
        "address": g.get("formatted_address", g.get("name")),
        "location": g.get("location"),  # "lng,lat"
        "city": g.get("city"),
        "district": g.get("district"),
    } for g in geocodes]
    return {"success": True, "data": {"results": results}}


@router.post("/reverse")
async def reverse_geocode(body: ReverseGeocodeRequest):
    """Convert coordinates to address."""
    location = f"{body.longitude},{body.latitude}"
    data = await _amap_get("/geocode/regeo", {
        "location": location,
        "extensions": "base",
    })
    regeo = data.get("regeocode", {})
    addr = regeo.get("formatted_address", "")
    pois = regeo.get("pois", [])[:5]
    return {"success": True, "data": {
        "address": addr,
        "city": regeo.get("addressComponent", {}).get("city", ""),
        "nearby": [{"name": p.get("name"), "type": p.get("type"), "address": p.get("address")} for p in pois],
    }}


@router.post("/route")
async def plan_route(body: RouteRequest):
    """Plan a walking route. Returns turn-by-turn steps optimized for voice guidance."""
    data = await _amap_get("/direction/walking", {
        "origin": body.origin,
        "destination": body.destination,
    })
    route = data.get("route", {})
    paths = route.get("paths", [])
    if not paths:
        return {"success": True, "data": {"distance": 0, "duration": 0, "steps": []}}

    path = paths[0]
    steps_raw = path.get("steps", [])
    total_distance = int(path.get("distance", 0))  # meters
    total_duration = int(path.get("duration", 0))  # seconds

    # Convert to voice-friendly steps
    steps = []
    for s in steps_raw:
        instruction = s.get("instruction", "")
        road = s.get("road", "")
        distance = int(s.get("distance", 0))
        duration = int(s.get("duration", 0))
        # Clean up HTML tags from instruction
        import re
        instruction = re.sub(r'<[^>]+>', '', instruction)
        steps.append({
            "instruction": instruction,
            "road": road,
            "distance_meters": distance,
            "duration_seconds": duration,
        })

    return {"success": True, "data": {
        "distance_meters": total_distance,
        "duration_minutes": round(total_duration / 60, 1),
        "steps": steps,
        "step_count": len(steps),
    }}


@router.post("/search")
async def search_poi(body: SearchRequest):
    """Search for nearby POIs (hospitals, restaurants, etc.)."""
    params = {
        "keywords": body.keywords,
        "offset": 10,
    }
    if body.city:
        params["city"] = body.city
    # If coordinates provided, do a nearby search
    if body.latitude != 0 and body.longitude != 0:
        params["location"] = f"{body.longitude},{body.latitude}"
        params["radius"] = "3000"
        params["sortrule"] = "distance"

    data = await _amap_get("/place/text", params)
    pois = data.get("pois", [])
    results = [{
        "name": p.get("name"),
        "address": p.get("address"),
        "location": p.get("location"),
        "distance": p.get("distance", ""),
        "type": p.get("type", ""),
    } for p in pois[:10]]
    return {"success": True, "data": {"results": results}}


@router.post("/navigate")
async def navigate(body: NavigateRequest, request: Request):
    """One-stop navigation: resolve destination → plan route → generate voice guidance.

    This is the main endpoint called by the app when user says "去最近的医院".
    """
    user = get_current_user(request)

    # Step 1: Resolve destination to coordinates
    dest_location = None
    dest_name = body.destination

    # Try geocoding first
    try:
        geo_params = {"address": body.destination}
        if body.city:
            geo_params["city"] = body.city
        geo_data = await _amap_get("/geocode/geo", geo_params)
        geocodes = geo_data.get("geocodes", [])
        if geocodes:
            dest_location = geocodes[0].get("location")
            dest_name = geocodes[0].get("formatted_address", body.destination)
    except Exception:
        pass

    # If geocoding failed, try POI search
    if not dest_location and body.current_lat != 0:
        try:
            search_data = await _amap_get("/place/text", {
                "keywords": body.destination,
                "location": f"{body.current_lng},{body.current_lat}",
                "radius": "5000",
                "sortrule": "distance",
            })
            pois = search_data.get("pois", [])
            if pois:
                dest_location = pois[0].get("location")
                dest_name = pois[0].get("name")
        except Exception:
            pass

    if not dest_location:
        return {"success": False, "error": f"无法找到'{body.destination}'的位置"}

    # Step 2: Plan route
    origin = body.origin
    if not origin and body.current_lat != 0:
        origin = f"{body.current_lng},{body.current_lat}"

    if not origin:
        return {"success": False, "error": "需要提供出发位置"}

    route_data = await _amap_get("/direction/walking", {
        "origin": origin,
        "destination": dest_location,
    })

    route = route_data.get("route", {})
    paths = route.get("paths", [])
    if not paths:
        return {"success": False, "error": "无法规划路线"}

    path = paths[0]
    steps_raw = path.get("steps", [])
    total_distance = int(path.get("distance", 0))
    total_duration = int(path.get("duration", 0))

    import re
    steps = []
    for s in steps_raw:
        instruction = re.sub(r'<[^>]+>', '', s.get("instruction", ""))
        steps.append({
            "instruction": instruction,
            "road": s.get("road", ""),
            "distance_meters": int(s.get("distance", 0)),
            "duration_seconds": int(s.get("duration", 0)),
        })

    # Step 3: Build voice guidance summary
    summary = f"前往{dest_name}，全程约{round(total_distance/1000,1)}公里，步行约{round(total_duration/60)}分钟。"
    if steps:
        summary += f"第一步：{steps[0]['instruction']}"

    return {"success": True, "data": {
        "destination": dest_name,
        "destination_location": dest_location,
        "origin_location": origin,
        "distance_meters": total_distance,
        "duration_minutes": round(total_duration / 60, 1),
        "step_count": len(steps),
        "steps": steps,
        "voice_summary": summary,
    }}
