"""Rebuild only the two finite tube direction cues. No model or texture writes."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PARTICLES = ROOT / "src/main/resources/Server/Particles/StrangeMatter"


def span(value):
    return {"Min": value, "Max": value}


def documents():
    for name, color in (("Take", "#56efff"), ("Send", "#c28bff")):
        ident = "SM_Tube_" + name
        yield PARTICLES / (ident + ".particlesystem"), {
            "Spawners": [{"SpawnerId": ident + "_Pulse"}],
            "LifeSpan": .3, "CullDistance": 20, "BoundingRadius": .15, "IsImportant": False,
        }
        yield PARTICLES / "Spawners" / (ident + "_Pulse.particlespawner"), {
            "Shape": "Sphere", "RenderMode": "BlendAdd", "LifeSpan": .05,
            "TotalParticles": span(1), "MaxConcurrentParticles": 1,
            "ParticleLifeSpan": span(.2), "SpawnRate": span(1), "SpawnBurst": True,
            "EmitOffset": {axis: span(0) for axis in "XYZ"},
            "ParticleRotationInfluence": "Billboard", "ParticleRotateWithSpawner": False,
            "TrailSpawnerPositionMultiplier": 0, "TrailSpawnerRotationMultiplier": 0,
            "LinearFiltering": True, "LightInfluence": 0,
            "InitialVelocity": {key: span(0) for key in ("Speed", "Yaw", "Pitch")},
            "Particle": {
                "Texture": "Particles/StrangeMatter/star.png", "FrameSize": {"Width": 128, "Height": 128},
                "ScaleRatioConstraint": "OneToOne", "UVOption": "None",
                "InitialAnimationFrame": {"Color": color, "Opacity": .9,
                    "Scale": {"X": span(.0022), "Y": span(.0022)}, "FrameIndex": span(0)},
                "Animation": {"0": {"Opacity": .9}, "100": {"Opacity": 0,
                    "Scale": {"X": span(.3), "Y": span(.3)}}},
            },
        }


if __name__ == "__main__":
    for path, document in documents():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print("Wrote 2 finite tube systems and 2 one-particle spawners. Existing art unchanged.")
