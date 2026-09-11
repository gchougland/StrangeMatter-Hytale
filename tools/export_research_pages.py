"""Import the user's original teaching pages without modifying the Minecraft source."""
from pathlib import Path
import json, re
from nullifier_content import add_teaching as add_nullifier_teaching

source = Path(r"C:/Users/gchou/Documents/Projects/StrangeMatter-1.20.1/strange-matter/src/main")
screen = (source / "java/com/hexvane/strangematter/client/screen/ResearchNodeInfoScreen.java").read_text(encoding="utf-8")
language = json.loads((source / "resources/assets/strangematter/lang/en_us.json").read_text(encoding="utf-8"))
methods = {
    "research":"Research", "field_scanner":"FieldScanner", "anomaly_resonator":"Resonator",
    "anomaly_shards":"AnomalyShards", "anomaly_types":"AnomalyTypes", "resonite":"Resonite",
    "resonant_energy":"ResonantEnergy", "reality_forge":"RealityForge", "reality_forge_category":"RealityForge",
    "resonance_condenser":"ResonanceCondenser", "containment_basics":"ContainmentBasics",
    "echoform_imprinter":"EchoformImprinter", "warp_gun":"WarpGun", "chrono_blister":"ChronoBlister",
    "stasis_projector":"StasisProjector", "rift_stabilizer":"RiftStabilizer", "levitation_pad":"LevitationPad",
    "hoverboard":"Hoverboard", "graviton_hammer":"GravitonHammer", "gravity_anomalies":"GravityAnomalies",
    "temporal_anomalies":"TemporalAnomalies", "spatial_anomalies":"SpatialAnomalies", "energy_anomalies":"EnergyAnomalies",
    "shadow_anomalies":"ShadowAnomalies", "cognitive_anomalies":"CognitiveAnomalies", "tinfoil_hat":"TinfoilHat",
}
output = {}
for node, method in methods.items():
    start = screen.index(f"private void initialize{method}Pages()")
    end = screen.find("\n    private ", start + 15)
    body = screen[start:end if end >= 0 else len(screen)]
    pages = []
    for added in re.finditer(r"pages\.add\((\w+)\);", body):
        var = added.group(1)
        before = body[:added.start()]
        fields = dict(re.findall(rf'{var}\.(title|content|recipeName|realityForgeRecipeName)\s*=\s*"([^"\n]*)";', before))
        assert "title" in fields and "content" in fields, (node, var, fields)
        page = {"title": language.get(fields["title"], fields["title"]), "content": language.get(fields["content"], fields["content"])}
        assert not page["content"].startswith("research.strangematter."), (node, page)
        recipe = fields.get("realityForgeRecipeName", fields.get("recipeName"))
        if recipe: page["recipe"] = recipe.removeprefix("strangematter:").removeprefix("reality_forge/")
        pages.append(page)
    assert pages, node
    output[node] = pages
add_nullifier_teaching(output)
target = Path(__file__).resolve().parents[1] / "src/main/resources/Server/StrangeMatter/Research/Teaching.json"
target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Imported {sum(map(len, output.values())) - 1} original teaching pages and the Anomaly Nullifier guide for {len(output)} research topics.")
