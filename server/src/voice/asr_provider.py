# -*- coding: utf-8 -*-
"""ASR provider selection shim.

The current runtime still uses the existing DashScope realtime ASR path in
app_main.py. This module provides a stable place to select providers later
without spreading provider-specific branching across the server.
"""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class ASRProviderConfig:
    name: str


class DashScopeASRProvider:
    name = "dashscope"

    def config(self) -> ASRProviderConfig:
        return ASRProviderConfig(name=self.name)


def get_asr_provider():
    provider = os.getenv("VISUS_ASR_PROVIDER", "dashscope").strip().lower()
    if provider != "dashscope":
        print(
            f"[PERF_ASR] provider={provider} not implemented, falling back to dashscope",
            flush=True,
        )
    selected = DashScopeASRProvider()
    print(f"[PERF_ASR] provider={selected.name}", flush=True)
    return selected
