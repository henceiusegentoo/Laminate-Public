#!/usr/bin/env python3
"""Generates the SVG diagrams embedded in docs/adoc/02-architecture.adoc.

Run with: python generate_diagrams.py
Requires: matplotlib (pip install matplotlib)

Regenerate after editing this file whenever the startup pipeline or the
classloader topology it depicts changes; the SVGs in docs/adoc/diagrams/ are
committed output, not generated at doc-build time.
"""
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch
from matplotlib.font_manager import FontProperties

OUT_DIR = "../adoc/diagrams"
PNG_PREVIEW = os.environ.get("DIAGRAM_PNG_PREVIEW") == "1"

INK = "#1b1f24"
MUTED = "#57606a"
BOX_BG = "#f6f8fa"
BOX_EDGE = "#57606a"
STAGE_A_BG = "#dbeafe"
STAGE_A_EDGE = "#2563eb"
STAGE_B_BG = "#fde7d0"
STAGE_B_EDGE = "#c2540a"
ARROW = "#24292f"

TITLE_FONT = FontProperties(family="DejaVu Sans", weight="bold", size=11)
BODY_FONT = FontProperties(family="DejaVu Sans", size=9)
LABEL_FONT = FontProperties(family="DejaVu Sans", style="italic", size=8.5)
LANE_FONT = FontProperties(family="DejaVu Sans", weight="bold", size=11)

TITLE_H = 0.42       # vertical space reserved for the title line
BULLET_H = 0.30       # vertical space per bullet line
PAD = 0.18            # top/bottom padding inside a box
GAP = 0.55            # gap between stacked boxes (grows if a label needs room)


def box_height(bullets):
    n = len(bullets) if bullets else 0
    return TITLE_H + n * BULLET_H + 2 * PAD


def draw_box(ax, cx, top_y, w, title, bullets=None, bg=BOX_BG, edge=BOX_EDGE):
    """Draws a box whose TOP edge is at top_y, returns (bottom_y, height)."""
    h = box_height(bullets)
    bottom_y = top_y - h
    cy = top_y - h / 2
    patch = FancyBboxPatch(
        (cx - w / 2, bottom_y), w, h,
        boxstyle="round,pad=0.02,rounding_size=0.08",
        linewidth=1.5, edgecolor=edge, facecolor=bg, zorder=3,
    )
    ax.add_patch(patch)
    title_y = top_y - PAD - TITLE_H / 2
    ax.text(cx, title_y, title, ha="center", va="center",
            fontproperties=TITLE_FONT, color=INK, zorder=4)
    if bullets:
        first_bullet_y = title_y - TITLE_H / 2 - BULLET_H / 2
        for i, b in enumerate(bullets):
            ax.text(cx, first_bullet_y - i * BULLET_H, f"•  {b}", ha="center", va="center",
                    fontproperties=BODY_FONT, color=MUTED, zorder=4)
    return bottom_y, h


def v_arrow(ax, x, y_top, y_bottom, label=None):
    ax.annotate(
        "", xy=(x, y_bottom), xytext=(x, y_top),
        arrowprops=dict(arrowstyle="-|>", color=ARROW, lw=1.6, shrinkA=1, shrinkB=1),
        zorder=2,
    )
    if label:
        ax.text(x + 4.15, (y_top + y_bottom) / 2, label, ha="left", va="center",
                fontproperties=LABEL_FONT, color=MUTED, zorder=4)


def curved_arrow(ax, x0, y0, x1, y1, rad=-0.35, label=None, label_dx=0.55):
    ax.annotate(
        "", xy=(x1, y1), xytext=(x0, y0),
        arrowprops=dict(arrowstyle="-|>", color=ARROW, lw=1.6,
                         connectionstyle=f"arc3,rad={rad}", shrinkA=2, shrinkB=2),
        zorder=2,
    )
    if label:
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        ax.text(mx + label_dx, my, label, ha="center", va="center",
                fontproperties=LABEL_FONT, color=MUTED, zorder=4)


def generate_startup_pipeline():
    cx = 5.6
    w = 7.6
    label_w = 3.6
    fig_w = cx + w / 2 + label_w
    steps = [
        ("LaminateMain.main()  (Main-Class)", None, BOX_BG, BOX_EDGE, None),
        ("RelaunchHandler.relaunch(args)", [
            "resolve java executable + self jar",
            "copy JVM args, strip a self-referential -javaagent:",
            "append 4x --add-opens (lang, lang.reflect, io, util = ALL-UNNAMED)",
            "append -javaagent:<self> -jar <self> [app-args]",
            "spawn child (inheritIO), wait, exit(child's exit code)",
        ], "#ffffff", BOX_EDGE, "no agent yet\n(laminate.agent.loaded unset)"),
        ("LaminateAgent.premain(args, inst)", [
            "runs before main() -- Premain-Class in manifest",
            "stash Instrumentation -> sysprop laminate.instrumentation",
            "set sysprop laminate.agent.loaded = \"true\"",
        ], STAGE_A_BG, STAGE_A_EDGE, "spawns child JVM"),
        ("LaminateMain.main()  (2nd time, agent loaded)", [
            "read Bundler-Main-Class from own manifest",
            "build bundlerLoader (URLClassLoader, parent = platform CL)",
            "build paperclipLoader (SelfFirstClassLoader, parent = bundlerLoader)",
            "set thread context CL = paperclipLoader",
        ], BOX_BG, BOX_EDGE, "loops back into\nLaminateMain.main()"),
        ("bootstrapHybridMixins()  --  STAGE A", [
            "scan plugin dirs, read hybrid descriptors",
            "one-time Mixin/Quilt service init (no config registration yet)",
            "queue each hybrid plugin + laminate-core-mixins' own config",
            "install LaminateMixinTransformer via Instrumentation.addTransformer",
        ], STAGE_A_BG, STAGE_A_EDGE, None),
        ("io.papermc.paperclip.Main.main()", [
            "defined by paperclipLoader (Paperclip.class.getClassLoader() IS it)",
            "builds serverLoader = new URLClassLoader(urls, ...getParent())",
            "  -> resolves to bundlerLoader: \"classloader interposition\"",
            "normal Paperclip/Paper boot begins",
        ], BOX_BG, BOX_EDGE, "Class.forName(bundlerMainClass,\ntrue, paperclipLoader).main()"),
        ("LaminateMixinTransformer.transform(...)  --  STAGE B triggered", [
            "1. pin loader as the game loader (LaminateQuiltLauncher)",
            "2. register laminate-core-mixins' own config",
            "3. register each hybrid plugin's mixin configs",
            "4. advance Mixin phases PREINIT -> INIT -> DEFAULT",
            "5. obtain real IMixinTransformer, publish via LaminateBridge",
            "6. retransform already-loaded NMC classes (safety net)",
        ], STAGE_B_BG, STAGE_B_EDGE, "first net.minecraft.*/io.papermc.*\nclass load anywhere in the JVM"),
        ("PluginInitializerManager.load()  (core mixin @TAIL)", [
            "cull failed hybrid plugins' provider entries",
            "log \"[Laminate] Laminate plugins (N): ...\" line",
        ], BOX_BG, BOX_EDGE, "every eligible class load from\nhere on is mixin-transformed"),
        ("Normal Paper server boot continues", [
            "plugins load, worlds load, server ticks",
        ], BOX_BG, BOX_EDGE, None),
    ]

    # Pass 1: compute every box's (top, bottom) without drawing anything, using
    # the exact same cursor arithmetic the drawing loop (pass 2, below) uses —
    # so the parent/child lane boundary can be placed exactly at a box edge
    # instead of an independently-guessed offset that can drift out of sync
    # (an earlier revision of this function did exactly that and produced
    # visibly overlapping lane-label text over the second box).
    caption_gap = 0.35
    positions = []
    prev_bottom = 0.0  # y = 0 is the caption line
    for i, (title, bullets, *_rest) in enumerate(steps):
        top = prev_bottom - (caption_gap if i == 0 else GAP)
        h = box_height(bullets)
        bottom = top - h
        positions.append((top, bottom))
        prev_bottom = bottom

    lowest = min(b for _, b in positions)
    total_h = 0.55 + (0 - lowest)
    lane_split = (positions[1][1] + positions[2][0]) / 2  # midway between box 1 and box 2

    fig, ax = plt.subplots(figsize=(fig_w, total_h))
    ax.set_xlim(0, fig_w)
    ax.set_ylim(lowest - 0.15, 0.4)
    ax.axis("off")

    lane_top = 0.4
    lane_bottom = lowest - 0.15
    ax.add_patch(plt.Rectangle((0.1, lane_split), fig_w - 0.2, lane_top - lane_split,
                                facecolor="#f0f3f7", edgecolor="none", zorder=0))
    ax.add_patch(plt.Rectangle((0.1, lane_bottom), fig_w - 0.2, lane_split - lane_bottom,
                                facecolor="#eaf3ea", edgecolor="none", zorder=0))
    ax.text(0.3, lane_top - 0.2, "PARENT JVM  (first invocation)",
            fontproperties=LANE_FONT, color="#334155", zorder=1)
    ax.text(0.3, lane_split - 0.25,
            "CHILD JVM  (relaunched -- or the only JVM if the agent was already attached)",
            fontproperties=LANE_FONT, color="#2f5030", zorder=1)

    ax.text(cx, 0.15, "java -jar laminate-server-<mcversion>.jar [app-args]",
            ha="center", va="center", fontproperties=BODY_FONT, color=INK, zorder=4, fontweight="bold")

    prev_bottom = 0.0
    for i, (title, bullets, bg, edge, arrow_label) in enumerate(steps):
        top, bottom = positions[i]
        v_arrow(ax, cx, prev_bottom, top, arrow_label)
        draw_box(ax, cx, top, w, title, bullets, bg, edge)
        prev_bottom = bottom

    plt.tight_layout(pad=0.3)
    fig.savefig(f"{OUT_DIR}/startup-pipeline.svg", format="svg", bbox_inches="tight")
    if PNG_PREVIEW:
        fig.savefig(f"{OUT_DIR}/startup-pipeline.png", format="png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def generate_classloader_topology():
    fig_w, fig_h = 10.2, 9.6
    fig, ax = plt.subplots(figsize=(fig_w, fig_h))
    ax.set_xlim(0, fig_w)
    ax.set_ylim(0, fig_h)
    ax.axis("off")

    cx = fig_w / 2
    top = fig_h - 0.15

    b1, h1 = draw_box(ax, cx, top, 4.6, "platform classloader", ["JDK boot/platform loader"])

    top2 = b1 - GAP
    b2, h2 = draw_box(ax, cx, top2, 8.4, "bundlerLoader  --  java.net.URLClassLoader", [
        "parent = platform classloader  |  classpath = this merged laminate-server-<ver>.jar",
        "hosts: LaminateMain's own classes, laminate-runtime, laminate-bridge,",
        "laminate-api, laminate-core-mixins, quilt-loader, sponge-mixin, mixinextras, asm, ...",
    ], STAGE_A_BG, STAGE_A_EDGE)
    v_arrow(ax, cx, b1, top2)

    # Two children side by side.
    left_cx = cx - 2.55
    right_cx = cx + 2.65
    top3 = b2 - GAP - 0.15

    b3, h3 = draw_box(ax, left_cx, top3, 4.6, "paperclipLoader", [
        "SelfFirstClassLoader (child-first)",
        "parent = bundlerLoader",
        "defines: io.papermc.paperclip.*",
        "Paperclip.class.getClassLoader() IS this",
    ])
    curved_arrow(ax, cx - 0.9, b2, left_cx + 0.3, top3, rad=0.3)

    b4, h4 = draw_box(ax, right_cx, top3, 4.6, "serverLoader", [
        "plain java.net.URLClassLoader",
        "parent = paperclipLoader.getParent()",
        "        = bundlerLoader (NOT paperclipLoader!)",
        "built BY paperclip -- defines the actual game",
        "classes (net.minecraft.*, io.papermc.*, craftbukkit)",
    ], STAGE_B_BG, STAGE_B_EDGE)
    curved_arrow(ax, cx + 0.9, b2, right_cx - 0.3, top3, rad=-0.3, label="\"interposition\"", label_dx=0.75)

    top5 = min(b3, b4) - GAP
    b5, h5 = draw_box(ax, right_cx, top5, 4.6, "ordinary Paper plugin classloaders", [
        "one per plugin jar, parented to serverLoader",
    ])
    v_arrow(ax, right_cx, b4, top5)

    # Separate box: per-hybrid-plugin resource loaders, dashed connection.
    top6 = top5
    b6, h6 = draw_box(ax, left_cx, top6, 4.6, "per-hybrid-plugin resource\nURLClassLoader(s)", [
        "created by laminate-runtime in Stage A",
        "parent = thread-context CL at Stage A",
        "  time = paperclipLoader",
        "resolves mixin config JSON / class",
        "  bytecode only for that one plugin",
    ], "#ffffff", "#8a8f98")
    ax.annotate(
        "", xy=(left_cx, top6), xytext=(left_cx, b3),
        arrowprops=dict(arrowstyle="-|>", color="#8a8f98", lw=1.3, linestyle=(0, (4, 3)),
                         shrinkA=2, shrinkB=2),
        zorder=2,
    )
    ax.text(left_cx + 2.55, (top6 + b3) / 2, "registered with LaminateQuiltLauncher --\nNOT part of the parent/child chain",
            ha="left", va="center", fontproperties=LABEL_FONT, color=MUTED)

    ax.set_ylim(min(b5, b6) - 0.15, fig_h)
    plt.tight_layout(pad=0.3)
    fig.savefig(f"{OUT_DIR}/classloader-topology.svg", format="svg", bbox_inches="tight")
    if PNG_PREVIEW:
        fig.savefig(f"{OUT_DIR}/classloader-topology.png", format="png", dpi=150, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    generate_startup_pipeline()
    generate_classloader_topology()
    print("Wrote", OUT_DIR)
