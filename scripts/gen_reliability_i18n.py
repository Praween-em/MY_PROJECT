# -*- coding: utf-8 -*-
"""Generate src/i18n/reliability.js — English / Hindi / Telugu (ASCII-safe source)."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src" / "i18n" / "reliability.js"


def U(escaped: str) -> str:
    return escaped.encode("ascii").decode("unicode_escape")


def js_str(s: str) -> str:
    return json_dumps_str(s)


def json_dumps_str(s: str) -> str:
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif o < 0x20:
            out.append(f"\\u{o:04x}")
        elif o < 0x7F:
            out.append(ch)
        else:
            out.append(f"\\u{o:04x}")
    out.append('"')
    return "".join(out)


def emit_obj(obj, indent: int = 0) -> str:
    pad = "  " * indent
    pad_in = "  " * (indent + 1)
    if isinstance(obj, list):
        if not obj:
            return "[]"
        body = ",\n".join(pad_in + emit_obj(x, indent + 1) for x in obj)
        return "[\n" + body + "\n" + pad + "]"
    if isinstance(obj, dict):
        if not obj:
            return "{}"
        parts = []
        for k, v in obj.items():
            key = k if k.isidentifier() else js_str(k)
            parts.append(f"{pad_in}{key}: {emit_obj(v, indent + 1)}")
        return "{\n" + ",\n".join(parts) + "\n" + pad + "}"
    if isinstance(obj, str):
        return js_str(obj)
    if isinstance(obj, bool):
        return "true" if obj else "false"
    if obj is None:
        return "null"
    return js_str(str(obj))


UI = {
    "en": {
        "title": "Service Reliability",
        "bannerOk": "Configured for maximum supported reliability",
        "bannerOkSub": "Android/OEM can still stop Accessibility after swipe-kill or battery limits.",
        "bannerWarn": "Action may be required",
        "bannerWarnSub": "Your device may restrict background operation. Follow the checklist below.",
        "status": "Status",
        "accessibility": "Accessibility Service",
        "enabled": "Enabled",
        "disabled": "Disabled",
        "master": "Auto-accept master",
        "on": "ON",
        "off": "OFF",
        "battery": "Battery optimization",
        "batteryOk": "OK",
        "batteryRestricted": "Restricted",
        "autostart": "Background / Auto-start",
        "autostartVerified": "Verified by you",
        "autostartCheck": "Check required",
        "oem": "OEM",
        "autostartHint": "Android cannot detect OEM Auto-start. If you already allowed it in phone settings, tap below.",
        "markVerified": "I've allowed Auto-start — mark as verified",
        "clearVerified": "Clear Auto-start verification",
        "actions": "Actions",
        "viewOem": "View OEM instructions",
        "hideOem": "Hide OEM instructions",
        "openA11y": "Open Accessibility Settings",
        "openBattery": "Open Battery Settings",
        "openAppInfo": "Open App Info",
        "improve": "Improve reliability",
        "oemMenuNote": "Menu names change by Android/OS version. Look for Autostart, Battery, and Background activity.",
        "language": "Language",
    },
    "hi": {
        "title": U(r"\u0938\u0947\u0935\u093e\u0020\u0935\u093f\u0936\u094d\u0935\u0938\u0928\u0940\u092f\u0924\u093e"),
        "bannerOk": U(r"\u0905\u0927\u093f\u0915\u0924\u092e\u0020\u0938\u092e\u0930\u094d\u0925\u093f\u0924\u0020\u0935\u093f\u0936\u094d\u0935\u0938\u0928\u0940\u092f\u0924\u093e\u0020\u0915\u0947\u0020\u0932\u093f\u090f\u0020\u0938\u0947\u091f"),
        "bannerOkSub": U(r"\u0938\u094d\u0935\u0948\u092a-\u0915\u093f\u0932\u0020\u092f\u093e\u0020\u092c\u0948\u091f\u0930\u0940\u0020\u0938\u0940\u092e\u093e\u0020\u0915\u0947\u0020\u092c\u093e\u0926\u0020Android/OEM\u0020\u0905\u092d\u0940\u0020\u092d\u0940\u0020Accessibility\u0020\u092c\u0902\u0926\u0020\u0915\u0930\u0020\u0938\u0915\u0924\u093e\u0020\u0939\u0948\u0964"),
        "bannerWarn": U(r"\u0915\u093e\u0930\u094d\u0930\u0935\u093e\u0908\u0020\u0915\u0940\u0020\u0906\u0935\u0936\u094d\u092f\u0915\u0924\u093e\u0020\u0939\u094b\u0020\u0938\u0915\u0924\u0940\u0020\u0939\u0948"),
        "bannerWarnSub": U(r"\u0906\u092a\u0915\u093e\u0020\u092b\u093c\u094b\u0928\u0020\u092c\u0948\u0915\u0917\u094d\u0930\u093e\u0909\u0902\u0921\u0020\u091a\u0932\u093e\u0928\u0947\u0020\u0915\u094b\u0020\u0930\u094b\u0915\u0020\u0938\u0915\u0924\u093e\u0020\u0939\u0948\u0964\u0020\u0928\u0940\u091a\u0947\u0020\u0926\u0940\u0020\u0917\u0908\u0020\u0938\u0942\u091a\u0940\u0020\u0926\u0947\u0916\u0947\u0902\u0964"),
        "status": U(r"\u0938\u094d\u0925\u093f\u0924\u093f"),
        "accessibility": U(r"\u090f\u0915\u094d\u0938\u0947\u0938\u093f\u092c\u093f\u0932\u093f\u091f\u0940\u0020\u0938\u0947\u0935\u093e"),
        "enabled": U(r"\u091a\u093e\u0932\u0942"),
        "disabled": U(r"\u092c\u0902\u0926"),
        "master": U(r"\u0911\u091f\u094b-\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f\u0020\u092e\u093e\u0938\u094d\u091f\u0930"),
        "on": "ON",
        "off": "OFF",
        "battery": U(r"\u092c\u0948\u091f\u0930\u0940\u0020\u0905\u0928\u0941\u0915\u0942\u0932\u0928"),
        "batteryOk": U(r"\u0920\u0940\u0915"),
        "batteryRestricted": U(r"\u092a\u094d\u0930\u0924\u093f\u092c\u0902\u0927\u093f\u0924"),
        "autostart": U(r"\u092c\u0948\u0915\u0917\u094d\u0930\u093e\u0909\u0902\u0921\u0020/\u0020\u0911\u091f\u094b-\u0938\u094d\u091f\u093e\u0930\u094d\u091f"),
        "autostartVerified": U(r"\u0906\u092a\u0928\u0947\u0020\u0938\u0924\u094d\u092f\u093e\u092a\u093f\u0924\u0020\u0915\u093f\u092f\u093e"),
        "autostartCheck": U(r"\u091c\u093e\u0901\u091a\u0020\u0906\u0935\u0936\u094d\u092f\u0915"),
        "oem": U(r"\u092b\u093c\u094b\u0928\u0020\u092c\u094d\u0930\u093e\u0902\u0921"),
        "autostartHint": U(r"Android\u0020\u0911\u091f\u094b-\u0938\u094d\u091f\u093e\u0930\u094d\u091f\u0020\u0916\u0941\u0926\u0020\u0928\u0939\u0940\u0902\u0020\u0926\u0947\u0916\u0020\u0938\u0915\u0924\u093e\u0964\u0020\u0905\u0917\u0930\u0020\u0906\u092a\u0928\u0947\u0020\u0938\u0947\u091f\u093f\u0902\u0917\u0020\u092e\u0947\u0902\u0020\u0905\u0928\u0941\u092e\u0924\u093f\u0020\u0926\u0947\u0020\u0926\u0940\u0020\u0939\u0948,\u0020\u0928\u0940\u091a\u0947\u0020\u091f\u0948\u092a\u0020\u0915\u0930\u0947\u0902\u0964"),
        "markVerified": U(r"\u092e\u0948\u0902\u0928\u0947\u0020\u0911\u091f\u094b-\u0938\u094d\u091f\u093e\u0930\u094d\u091f\u0020\u0905\u0928\u0941\u092e\u0924\u093f\u0020\u0926\u0940\u0020\u2014\u0020\u0938\u0924\u094d\u092f\u093e\u092a\u093f\u0924\u0020\u0915\u0930\u0947\u0902"),
        "clearVerified": U(r"\u0911\u091f\u094b-\u0938\u094d\u091f\u093e\u0930\u094d\u091f\u0020\u0938\u0924\u094d\u092f\u093e\u092a\u0928\u0020\u0939\u091f\u093e\u090f\u0901"),
        "actions": U(r"\u0915\u093e\u0930\u094d\u0930\u0935\u093e\u0908"),
        "viewOem": U(r"OEM\u0020\u0928\u093f\u0930\u094d\u0926\u0947\u0936\u0020\u0926\u0947\u0916\u0947\u0902"),
        "hideOem": U(r"OEM\u0020\u0928\u093f\u0930\u094d\u0926\u0947\u0936\u0020\u091b\u093f\u092a\u093e\u090f\u0901"),
        "openA11y": U(r"\u090f\u0915\u094d\u0938\u0947\u0938\u093f\u092c\u093f\u0932\u093f\u091f\u0940\u0020\u0938\u0947\u091f\u093f\u0902\u0917\u0020\u0916\u094b\u0932\u0947\u0902"),
        "openBattery": U(r"\u092c\u0948\u091f\u0930\u0940\u0020\u0938\u0947\u091f\u093f\u0902\u0917\u0020\u0916\u094b\u0932\u0947\u0902"),
        "openAppInfo": U(r"\u0910\u092a\u0020\u091c\u093e\u0928\u0915\u093e\u0930\u0940\u0020\u0916\u094b\u0932\u0947\u0902"),
        "improve": U(r"\u0935\u093f\u0936\u094d\u0935\u0938\u0928\u0940\u092f\u0924\u093e\u0020\u092c\u0922\u093c\u093e\u090f\u0901"),
        "oemMenuNote": U(r"\u092e\u0947\u0928\u0942\u0020\u0928\u093e\u092e\u0020Android/OS\u0020\u0915\u0947\u0020\u0905\u0928\u0941\u0938\u093e\u0930\u0020\u092c\u0926\u0932\u0924\u0947\u0020\u0939\u0948\u0902\u0964\u0020Autostart,\u0020Battery,\u0020Background\u0020\u0926\u0947\u0916\u0947\u0902\u0964"),
        "language": U(r"\u092d\u093e\u0937\u093e"),
    },
    "te": {
        "title": U(r"\u0c38\u0c47\u0c35\u0020\u0c35\u0c3f\u0c36\u0c4d\u0c35\u0c38\u0c28\u0c40\u0c2f\u0c24"),
        "bannerOk": U(r"\u0c17\u0c30\u0c3f\u0c37\u0c4d\u0c1f\u0020\u0c2e\u0c26\u0c4d\u0c26\u0c24\u0c41\u0020\u0c35\u0c3f\u0c36\u0c4d\u0c35\u0c38\u0c28\u0c40\u0c2f\u0c24\u0c15\u0c41\u0020\u0c38\u0c46\u0c1f\u0c4d\u0020\u0c05\u0c2f\u0c3f\u0c02\u0c26\u0c3f"),
        "bannerOkSub": U(r"\u0c38\u0c4d\u0c35\u0c48\u0c2a\u0c4d-\u0c15\u0c3f\u0c32\u0c4d\u0020\u0c32\u0c47\u0c26\u0c3e\u0020\u0c2c\u0c4d\u0c2f\u0c3e\u0c1f\u0c30\u0c40\u0020\u0c2a\u0c30\u0c3f\u0c2e\u0c3f\u0c24\u0c41\u0c32\u0020\u0c24\u0c30\u0c4d\u0c35\u0c3e\u0c24\u0020\u0c15\u0c42\u0c21\u0c3e\u0020Android/OEM\u0020Accessibility\u0020\u0c28\u0c3f\u0020\u0c06\u0c2a\u0c35\u0c1a\u0c4d\u0c1a\u0c41."),
        "bannerWarn": U(r"\u0c1a\u0c30\u0c4d\u0c2f\u0020\u0c05\u0c35\u0c38\u0c30\u0c02\u0020\u0c15\u0c3e\u0c35\u0c1a\u0c4d\u0c1a\u0c41"),
        "bannerWarnSub": U(r"\u0c2e\u0c40\u0020\u0c2b\u0c4b\u0c28\u0c4d\u0020\u0c2c\u0c4d\u0c2f\u0c3e\u0c15\u0c4d\u200c\u0c17\u0c4d\u0c30\u0c4c\u0c02\u0c21\u0c4d\u0020\u0c2a\u0c28\u0c3f\u0c28\u0c3f\u0020\u0c28\u0c3f\u0c30\u0c4b\u0c27\u0c3f\u0c02\u0c1a\u0c35\u0c1a\u0c4d\u0c1a\u0c41.\u0020\u0c15\u0c3f\u0c02\u0c26\u0c3f\u0020\u0c1c\u0c3e\u0c2c\u0c3f\u0c24\u0c3e\u0020\u0c1a\u0c42\u0c21\u0c02\u0c21\u0c3f."),
        "status": U(r"\u0c38\u0c4d\u0c25\u0c3f\u0c24\u0c3f"),
        "accessibility": U(r"\u0c2f\u0c3e\u0c15\u0c4d\u0c38\u0c46\u0c38\u0c3f\u0c2c\u0c3f\u0c32\u0c3f\u0c1f\u0c40\u0020\u0c38\u0c47\u0c35"),
        "enabled": U(r"\u0c06\u0c28\u0c4d"),
        "disabled": U(r"\u0c06\u0c2b\u0c4d"),
        "master": U(r"\u0c06\u0c1f\u0c4b-\u0c05\u0c15\u0c4d\u0c38\u0c46\u0c2a\u0c4d\u0c1f\u0c4d\u0020\u0c2e\u0c3e\u0c38\u0c4d\u0c1f\u0c30\u0c4d"),
        "on": "ON",
        "off": "OFF",
        "battery": U(r"\u0c2c\u0c4d\u0c2f\u0c3e\u0c1f\u0c30\u0c40\u0020\u0c06\u0c2a\u0c4d\u0c1f\u0c3f\u0c2e\u0c48\u0c1c\u0c47\u0c37\u0c28\u0c4d"),
        "batteryOk": U(r"\u0c38\u0c30\u0c47"),
        "batteryRestricted": U(r"\u0c28\u0c3f\u0c2f\u0c02\u0c24\u0c4d\u0c30\u0c3f\u0c02\u0c1a\u0c2c\u0c21\u0c3f\u0c02\u0c26\u0c3f"),
        "autostart": U(r"\u0c2c\u0c4d\u0c2f\u0c3e\u0c15\u0c4d\u200c\u0c17\u0c4d\u0c30\u0c4c\u0c02\u0c21\u0c4d\u0020/\u0020\u0c06\u0c1f\u0c4b-\u0c38\u0c4d\u0c1f\u0c3e\u0c30\u0c4d\u0c1f\u0c4d"),
        "autostartVerified": U(r"\u0c2e\u0c40\u0c30\u0c41\u0020\u0c28\u0c3f\u0c30\u0c4d\u0c27\u0c3e\u0c30\u0c3f\u0c02\u0c1a\u0c3e\u0c30\u0c41"),
        "autostartCheck": U(r"\u0c24\u0c28\u0c3f\u0c16\u0c40\u0020\u0c05\u0c35\u0c38\u0c30\u0c02"),
        "oem": U(r"\u0c2b\u0c4b\u0c28\u0c4d\u0020\u0c2c\u0c4d\u0c30\u0c3e\u0c02\u0c21\u0c4d"),
        "autostartHint": U(r"Android\u0020\u0c06\u0c1f\u0c4b-\u0c38\u0c4d\u0c1f\u0c3e\u0c30\u0c4d\u0c1f\u0c4d\u200c\u0c28\u0c41\u0020\u0c38\u0c4d\u0c35\u0c2f\u0c02\u0c17\u0c3e\u0020\u0c1a\u0c42\u0c21\u0c32\u0c47\u0c26\u0c41.\u0020\u0c38\u0c46\u0c1f\u0c4d\u0c1f\u0c3f\u0c02\u0c17\u0c4d\u0c38\u0c4d\u200c\u0c32\u0c4b\u0020\u0c05\u0c28\u0c41\u0c2e\u0c24\u0c3f\u0020\u0c07\u0c1a\u0c4d\u0c1a\u0c3f\u0020\u0c09\u0c02\u0c1f\u0c47\u0020\u0c15\u0c3f\u0c02\u0c26\u0020\u0c1f\u0c4d\u0c2f\u0c3e\u0c2a\u0c4d\u0020\u0c1a\u0c47\u0c2f\u0c02\u0c21\u0c3f."),
        "markVerified": U(r"\u0c28\u0c47\u0c28\u0c41\u0020\u0c06\u0c1f\u0c4b-\u0c38\u0c4d\u0c1f\u0c3e\u0c30\u0c4d\u0c1f\u0c4d\u0020\u0c05\u0c28\u0c41\u0c2e\u0c24\u0c3f\u0c02\u0c1a\u0c3e\u0c28\u0c41\u0020\u2014\u0020\u0c28\u0c3f\u0c30\u0c4d\u0c27\u0c3e\u0c30\u0c3f\u0c02\u0c1a\u0c41"),
        "clearVerified": U(r"\u0c06\u0c1f\u0c4b-\u0c38\u0c4d\u0c1f\u0c3e\u0c30\u0c4d\u0c1f\u0c4d\u0020\u0c28\u0c3f\u0c30\u0c4d\u0c27\u0c3e\u0c30\u0c23\u0020\u0c24\u0c4a\u0c32\u0c17\u0c3f\u0c02\u0c1a\u0c41"),
        "actions": U(r"\u0c1a\u0c30\u0c4d\u0c2f\u0c32\u0c41"),
        "viewOem": U(r"OEM\u0020\u0c38\u0c42\u0c1a\u0c28\u0c32\u0c41\u0020\u0c1a\u0c42\u0c21\u0c02\u0c21\u0c3f"),
        "hideOem": U(r"OEM\u0020\u0c38\u0c42\u0c1a\u0c28\u0c32\u0c41\u0020\u0c26\u0c3e\u0c1a\u0c02\u0c21\u0c3f"),
        "openA11y": U(r"\u0c2f\u0c3e\u0c15\u0c4d\u0c38\u0c46\u0c38\u0c3f\u0c2c\u0c3f\u0c32\u0c3f\u0c1f\u0c40\u0020\u0c38\u0c46\u0c1f\u0c4d\u0c1f\u0c3f\u0c02\u0c17\u0c4d\u0c38\u0c4d\u0020\u0c24\u0c46\u0c30\u0c35\u0c02\u0c21\u0c3f"),
        "openBattery": U(r"\u0c2c\u0c4d\u0c2f\u0c3e\u0c1f\u0c30\u0c40\u0020\u0c38\u0c46\u0c1f\u0c4d\u0c1f\u0c3f\u0c02\u0c17\u0c4d\u0c38\u0c4d\u0020\u0c24\u0c46\u0c30\u0c35\u0c02\u0c21\u0c3f"),
        "openAppInfo": U(r"\u0c2f\u0c3e\u0c2a\u0c4d\u0020\u0c38\u0c2e\u0c3e\u0c1a\u0c3e\u0c30\u0c02\u0020\u0c24\u0c46\u0c30\u0c35\u0c02\u0c21\u0c3f"),
        "improve": U(r"\u0c35\u0c3f\u0c36\u0c4d\u0c35\u0c38\u0c28\u0c40\u0c2f\u0c24\u0020\u0c2a\u0c46\u0c02\u0c1a\u0c02\u0c21\u0c3f"),
        "oemMenuNote": U(r"\u0c2e\u0c46\u0c28\u0c42\u0020\u0c2a\u0c47\u0c30\u0c4d\u0c32\u0c41\u0020Android/OS\u0020\u0c35\u0c46\u0c30\u0c4d\u0c37\u0c28\u0c4d\u200c\u0c24\u0c4b\u0020\u0c2e\u0c3e\u0c30\u0c24\u0c3e\u0c2f\u0c3f.\u0020Autostart,\u0020Battery,\u0020Background\u0020\u0c1a\u0c42\u0c21\u0c02\u0c21\u0c3f."),
        "language": U(r"\u0c2d\u0c3e\u0c37"),
    },
}

# OEM menu labels on phones are English — keep steps English for all languages.
OEM_EN = {
    "xiaomi": {
        "title": "Xiaomi / Redmi / POCO",
        "steps": [
            "Open Settings → Apps → Permissions → Autostart (or App launch).",
            "Enable Auto-start / allow background launch for AG rider.",
            "Open AG rider App info → Battery → No restrictions / Allow background.",
            "On HyperOS/MIUI, also check Battery saver → No restrictions.",
            "Keep Accessibility Service enabled for AG rider.",
            "Optional: lock AG rider in Recents.",
        ],
    },
    "oppo": {
        "title": "OPPO (ColorOS)",
        "steps": [
            "Settings → Apps → App management → AG rider.",
            "Enable Auto-launch / Auto-start if shown.",
            "Battery → Allow background activity.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "vivo": {
        "title": "Vivo (Funtouch / OriginOS)",
        "steps": [
            "Settings → Apps → AG rider.",
            "Battery → Allow background activity.",
            "Enable Auto-start / High background power if shown.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "realme": {
        "title": "realme (realme UI)",
        "steps": [
            "Settings → Apps → App management → AG rider.",
            "Battery usage → Allow background activity.",
            "Enable Auto-launch / Startup if available.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "oneplus": {
        "title": "OnePlus",
        "steps": [
            "Settings → Apps → App management → AG rider.",
            "Battery → Allow background / Unrestricted.",
            "Enable Auto-launch if shown.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "samsung": {
        "title": "Samsung (One UI)",
        "steps": [
            "Settings → Battery → Background usage limits.",
            "Add AG rider to Never sleeping apps.",
            "Remove from Sleeping / Deep sleeping apps if listed.",
            "Avoid aggressive Power saving while taking rides.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "motorola": {
        "title": "Motorola",
        "steps": [
            "Settings → Apps → AG rider.",
            "App battery usage → Allow background / Unrestricted.",
            "Check Battery optimization is not restricting the app.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "pixel": {
        "title": "Google Pixel / Stock Android",
        "steps": [
            "Settings → Apps → AG rider → App battery usage.",
            "Choose Unrestricted for maximum background reliability.",
            "Note: Unrestricted can use more battery.",
            "Keep Accessibility Service enabled.",
        ],
    },
    "generic": {
        "title": "Android (other OEM)",
        "steps": [
            "Settings → Apps → AG rider.",
            "Allow background activity / disable battery restriction.",
            "Look for Autostart / Auto-launch if available.",
            "Keep Accessibility Service enabled.",
        ],
    },
}

OEM = {"en": OEM_EN, "hi": OEM_EN, "te": OEM_EN}

LANG_LABELS = {
    "en": "English",
    "hi": U(r"\u0939\u093f\u0902\u0926\u0940"),
    "te": U(r"\u0c24\u0c46\u0c32\u0c41\u0c17\u0c41"),
}


def main() -> None:
    out = f"""/**
 * Service Reliability UI — English / Hindi / Telugu
 * Generated by scripts/gen_reliability_i18n.py — do not hand-edit Telugu/Hindi.
 */

export const RELIABILITY_LANGS = [
  {{ id: 'en', label: {js_str(LANG_LABELS['en'])} }},
  {{ id: 'hi', label: {js_str(LANG_LABELS['hi'])} }},
  {{ id: 'te', label: {js_str(LANG_LABELS['te'])} }},
];

export const LANG_STORAGE_KEY = '@superridex/reliability_lang';

const UI = {emit_obj(UI)};

const OEM = {emit_obj(OEM)};

export function t(lang, key) {{
  const pack = UI[lang] || UI.en;
  return pack[key] ?? UI.en[key] ?? key;
}}


export function getOemGuide(lang, oemId) {{
  const pack = OEM[lang] || OEM.en;
  return pack[oemId] || pack.generic || OEM.en.generic;
}}

"""
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(out.rstrip() + "\n", encoding="utf-8", newline="\n")
    text = OUT.read_text(encoding="utf-8")
    assert "\ufffd" not in text, "replacement char found"
    assert U(r"\u0c38\u0c47\u0c35") in text or "\\u0c38\\u0c47\\u0c35" in text
    print(f"Wrote {OUT} ({OUT.stat().st_size} bytes)")
    print("hi title ok:", UI["hi"]["title"].encode("unicode_escape").decode("ascii"))
    print("te title ok:", UI["te"]["title"].encode("unicode_escape").decode("ascii"))


if __name__ == "__main__":
    main()
