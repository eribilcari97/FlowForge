import { Component } from '@angular/core';

@Component({
  selector: 'app-auth-intro',
  template: `
    <p class="lede">
      Build workflows from HTTP calls, transforms, emails and delays. FlowForge runs each step once
      the steps it depends on have succeeded, retries the ones that fail, and runs them on a
      schedule.
    </p>
    <svg
      viewBox="0 0 320 180"
      role="img"
      aria-label="Example run: two steps succeeded, one running, one waiting"
    >
      <path d="M104 44 C127 44 127 90 150 90 M104 136 C127 136 127 90 150 90 M246 90 L266 90" />
      <g transform="translate(8 26)" data-status="SUCCEEDED">
        <rect width="96" height="36" rx="5" />
        <text x="12" y="23">fetch_orders</text>
      </g>
      <g transform="translate(8 118)" data-status="SUCCEEDED">
        <rect width="96" height="36" rx="5" />
        <text x="12" y="23">load_stock</text>
      </g>
      <g transform="translate(150 72)" data-status="RUNNING" class="hot">
        <rect width="96" height="36" rx="5" />
        <text x="12" y="23">build_report</text>
      </g>
      <g transform="translate(266 72)" data-status="PENDING">
        <rect width="54" height="36" rx="5" />
        <text x="12" y="23">email</text>
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: 32px;
      padding: 32px;
      background: var(--ff-ink);
      color: #dfe5ea;
    }

    .lede {
      margin: 0;
      max-width: 36ch;
      font-size: 1.125rem;
      line-height: 1.5;
      color: #ffffff;
    }

    svg {
      width: 100%;
      max-width: 360px;
      overflow: visible;
    }

    path {
      fill: none;
      stroke: #4b5b69;
      stroke-width: 1.5;
    }

    rect {
      fill: #1f2e3b;
      stroke: #3a4a58;
    }

    [data-status] rect {
      stroke: color-mix(in srgb, var(--status) 70%, #1f2e3b);
    }

    .hot rect {
      stroke: var(--ff-run);
      stroke-width: 1.5;
      animation: heat 1.6s ease-in-out infinite;
    }

    text {
      fill: #dfe5ea;
      font: 500 9.5px var(--ff-mono);
    }

    @keyframes heat {
      50% {
        stroke-width: 4;
      }
    }
  `,
})
export class AuthIntro {}
