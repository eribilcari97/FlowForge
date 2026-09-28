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
              <rect [attr.width]="node.width" [attr.height]="node.height" rx="8" />
              <text x="12" y="23" class="graph-label">{{ shorten(node.label) }}</text>
              <text x="12" y="42" class="graph-detail">{{ node.detail }}</text>
            </g>
          }
        </svg>
      </div>
    }
  `,
  styles: `
    .graph-scroll {
      overflow-x: auto;
      padding: 8px 0;
    }

    .graph-empty {
      color: var(--mat-sys-on-surface-variant);
    }

    .graph-edge {
      fill: none;
      stroke: var(--mat-sys-outline);
      stroke-width: 1.5;
    }

    .graph-arrow {
      fill: var(--mat-sys-outline);
    }

    .graph-node rect {
      fill: var(--mat-sys-surface-container);
      stroke: var(--mat-sys-outline-variant);
      stroke-width: 1.5;
    }

    .graph-node.clickable {
      cursor: pointer;
    }

    .graph-node.clickable:hover rect {
      stroke: var(--mat-sys-primary);
    }

    .graph-label {
      font: var(--mat-sys-label-large);
      fill: var(--mat-sys-on-surface);
    }

    .graph-detail {
      font: var(--mat-sys-label-small);
      fill: var(--mat-sys-on-surface-variant);
    }

    [data-status='SUCCEEDED'] rect {
      fill: var(--mat-sys-tertiary-container);
      stroke: var(--mat-sys-tertiary);
    }

    [data-status='RUNNING'] rect,
    [data-status='READY'] rect {
      fill: var(--mat-sys-primary-container);
      stroke: var(--mat-sys-primary);
    }

    [data-status='FAILED'] rect {
      fill: var(--mat-sys-error-container);
      stroke: var(--mat-sys-error);
    }

    [data-status='SKIPPED'] rect,
    [data-status='CANCELLED'] rect {
      fill: var(--mat-sys-surface-container-highest);
      stroke-dasharray: 4 3;
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
