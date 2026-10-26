#!/usr/bin/env python3
"""Build index.html from index.template.html by inlining stations.json.

    python3 build.py

The page is a single HTML file so it can be published as a claude.ai artifact
or opened anywhere; the station harmonics ride inside it.
"""
from pathlib import Path

here = Path(__file__).parent
tpl = (here / "index.template.html").read_text()
stations = (here / "stations.json").read_text().replace("</", "<\\/")
(here / "index.html").write_text(tpl.replace("__STATIONS__", stations))
print("wrote index.html")
