import { Component, computed, input, output } from '@angular/core';

import { GraphEdgeInput, GraphNodeInput, layoutGraph } from './graph-layout';

const MAX_LABEL_LENGTH = 22;

@Component({
  selector: 'app-dependency-graph',
  template: `
    @if (layout().nodes.length === 0) {
      <p class="graph-empty">Nothing to show yet.</p>
    } @else {
      <div class="graph-scroll">
        <svg
          class="graph"
          [attr.width]="layout().width"
          [attr.height]="layout().height"
          [attr.viewBox]="'0 0 ' + layout().width + ' ' + layout().height"
          role="img"
          [attr.aria-label]="ariaLabel()"
        >
          <defs>
            <marker
              id="graph-arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" class="graph-arrow" />
            </marker>
          </defs>
          @for (edge of layout().edges; track edge.from + '>' + edge.to) {
            <path class="graph-edge" [attr.d]="edge.path" marker-end="url(#graph-arrow)" />
          }
          @for (node of layout().nodes; track node.id) {
            <g
              class="graph-node"
              [attr.data-id]="node.id"
              [attr.data-status]="node.status ?? null"
              [class.clickable]="clickable()"
              [attr.transform]="'translate(' + node.x + ',' + node.y + ')'"
              (click)="clickable() && nodeClick.emit(node.id)"
            >
              <title>{{ node.label }} · {{ node.detail }}</title>
              <rect class="body" [attr.width]="node.width" [attr.height]="node.height" rx="6" />
              <rect class="strip" x="0" y="0" width="4" [attr.height]="node.height" rx="2" />
              <text x="14" y="23" class="graph-label">{{ shorten(node.label) }}</text>
              <text x="14" y="42" class="graph-detail">{{ node.detail }}</text>
            </g>
          }
        </svg>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .graph-scroll {
      overflow-x: auto;
      padding: 20px;
      background-color: var(--ff-panel);
      background-image: radial-gradient(var(--ff-rule) 1px, transparent 1px);
      background-size: 16px 16px;
      border: 1px solid var(--ff-rule);
      border-radius: var(--ff-radius);
    }

    .graph {
      display: block;
      margin: 0 auto;
      overflow: visible;
    }

    .graph-empty {
      color: var(--ff-ink-2);
    }

    .graph-edge {
      fill: none;
      stroke: var(--ff-rule-strong);
      stroke-width: 1.5;
    }

    .graph-arrow {
      fill: var(--ff-rule-strong);
    }

    .graph-node .body {
      fill: var(--ff-panel);
      stroke: var(--ff-rule-strong);
      stroke-width: 1;
    }

    .graph-node .strip {
      fill: var(--status, var(--ff-ink));
    }

    .graph-node.clickable {
      cursor: pointer;
    }

    .graph-node.clickable:hover .body {
      stroke: var(--ff-ink);
      stroke-width: 1.5;
    }

    .graph-label {
      font: 500 12px var(--ff-mono);
      fill: var(--ff-ink);
    }

    .graph-detail {
      font: 500 11px var(--ff-sans);
      fill: var(--ff-ink-2);
    }

    [data-status] .body {
      fill: color-mix(in srgb, var(--status-wash) 55%, var(--ff-panel));
      stroke: color-mix(in srgb, var(--status) 55%, var(--ff-panel));
    }

    [data-status='RUNNING'] .body {
      stroke: var(--ff-run);
      stroke-width: 1.5;
      animation: node-heat 1.6s ease-in-out infinite;
    }

    [data-status='SKIPPED'] .body,
    [data-status='CANCELLED'] .body {
      fill: var(--ff-sunken);
      stroke-dasharray: 4 3;
    }

    [data-status='SKIPPED'] .graph-label,
    [data-status='CANCELLED'] .graph-label {
      fill: var(--ff-ink-2);
    }

    @keyframes node-heat {
      50% {
        stroke-width: 4;
      }
    }
  `,
})
export class DependencyGraph {
  readonly nodes = input.required<GraphNodeInput[]>();
  readonly edges = input.required<GraphEdgeInput[]>();
  readonly clickable = input(false);
  readonly nodeClick = output<string>();

  protected readonly layout = computed(() => layoutGraph(this.nodes(), this.edges()));
  protected readonly ariaLabel = computed(
    () =>
      `Dependency graph with ${this.nodes().length} steps and ${this.edges().length} dependencies`,
  );

  protected shorten(label: string): string {
    return label.length > MAX_LABEL_LENGTH ? label.slice(0, MAX_LABEL_LENGTH - 1) + '…' : label;
  }
}
