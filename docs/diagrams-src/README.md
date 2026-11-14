# Diagram sources

`generate_diagrams.py` produces the SVGs embedded in `docs/adoc/02-architecture.adoc`
(`docs/adoc/diagrams/startup-pipeline.svg` and `classloader-topology.svg`) using plain
matplotlib — no Graphviz/PlantUML/Kroki dependency, no network access, no external renderer.

Regenerate after editing this script:

```sh
pip install matplotlib
python generate_diagrams.py
```

The generated SVGs are committed alongside the docs (not built as part of `./gradlew build`),
so a doc reader or renderer never needs Python/matplotlib installed — only someone editing the
diagrams themselves does.
