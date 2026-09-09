"""Target only held interaction behavior; preserve models, textures and other item properties."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ITEMS = ROOT / "src/main/resources/Server/Item/Items/StrangeMatter"

def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

def main():
    path = ITEMS / "SM_Graviton_Hammer.json"
    item = json.loads(path.read_text(encoding="utf-8-sig"))
    item["Interactions"]["Primary"] = {
        "Interactions": [{
            "Type": "Simple", "RunTime": 0.13,
            "Effects": {"ItemAnimationId": "Mine", "WorldSoundEventId": "SFX_Tool_T1_Swing",
                        "LocalSoundEventId": "SFX_Pickaxe_T1_Swing_Down_Local", "CameraEffect": "Pickaxe_Mine"},
            "Next": {"Type": "SM_Use", "Action": "hammer0", "Next": {"Type": "Simple", "RunTime": 0.57}}
        }],
        "Cooldown": {"Id": "SM_Graviton_Hammer_Swing", "Cooldown": 0.7, "ClickBypass": False},
        "RequireNewClick": False,
    }
    charging = item["Interactions"]["Secondary"]["Interactions"][0]
    charging["Effects"] = {"WorldSoundEventId": "SM_Graviton_Chargeup_SFX", "ClearSoundEventOnFinish": True}
    write(path, item)
    for path in ITEMS.glob("SM_Containment_Capsule_*.json"):
        item = json.loads(path.read_text(encoding="utf-8-sig"))
        for action in ("Primary", "Secondary", "Use"):
            item["Interactions"][action] = {
                "Interactions": [{"Type": "Simple", "RunTime": 0.12, "Effects": {"ItemAnimationId": "Throw"},
                                  "Next": {"Type": "SM_Use", "Action": "capsule_throw",
                                           "Next": {"Type": "Simple", "RunTime": 0.63}}}],
                "RequireNewClick": True,
                "Cooldown": {"Id": "SM_Capsule_Throw", "Cooldown": 0.75, "ClickBypass": False},
            }
        write(path, item)

if __name__ == "__main__":
    main()
