import { AbstractControl, ValidationErrors } from '@angular/forms';

import {
  DelayStepConfig,
  EmailStepConfig,
  HttpMethod,
  HttpStepConfig,
  Step,
  TransformStepConfig,
} from './workflow.service';

export interface HttpConfigForm {
  method: HttpMethod;
  url: string;
  headers: string;
  body: string;
  expectedStatus: string;
}

export interface DelayConfigForm {
  duration: string;
}

export const emptyHttpConfig: HttpConfigForm = {
  method: 'GET',
  url: '',
  headers: '',
  body: '',
  expectedStatus: '',
};

export interface TransformConfigForm {
  expression: string;
}

export interface EmailConfigForm {
  to: string;
  cc: string;
  subject: string;
  text: string;
  html: string;
}

export const emptyDelayConfig: DelayConfigForm = { duration: 'PT30S' };

export const emptyTransformConfig: TransformConfigForm = { expression: '' };

export const emptyEmailConfig: EmailConfigForm = {
  to: '',
  cc: '',
  subject: '',
  text: '',
  html: '',
};

export function toHttpConfig(form: HttpConfigForm): HttpStepConfig {
  const config: HttpStepConfig = { method: form.method, url: form.url.trim() };
  if (form.headers.trim()) {
    config.headers = JSON.parse(form.headers);
  }
  if (form.body.trim()) {
    config.body = JSON.parse(form.body);
  }
  if (form.expectedStatus.trim()) {
    config.expectedStatus = form.expectedStatus.split(',').map((code) => Number(code.trim()));
  }
  return config;
}

export function toDelayConfig(form: DelayConfigForm): DelayStepConfig {
  return { duration: form.duration.trim() };
}

export function httpConfigForm(step: Step): HttpConfigForm {
  const config = step.config as HttpStepConfig;
  return {
    method: config.method,
    url: config.url,
    headers: config.headers ? JSON.stringify(config.headers, null, 2) : '',
    body:
      config.body !== undefined && config.body !== null ? JSON.stringify(config.body, null, 2) : '',
    expectedStatus: config.expectedStatus?.join(', ') ?? '',
  };
}

export function delayConfigForm(step: Step): DelayConfigForm {
  return { duration: (step.config as DelayStepConfig).duration };
}

export function toTransformConfig(form: TransformConfigForm): TransformStepConfig {
  return { expression: form.expression.trim() };
}

export function toEmailConfig(form: EmailConfigForm): EmailStepConfig {
  const config: EmailStepConfig = {
    to: splitAddresses(form.to),
    subject: form.subject.trim(),
  };
  const cc = splitAddresses(form.cc);
  if (cc.length) {
    config.cc = cc;
  }
  if (form.text.trim()) {
    config.text = form.text;
  }
  if (form.html.trim()) {
    config.html = form.html;
  }
  return config;
}

export function transformConfigForm(step: Step): TransformConfigForm {
  return { expression: (step.config as TransformStepConfig).expression };
}

export function emailConfigForm(step: Step): EmailConfigForm {
  const config = step.config as EmailStepConfig;
  return {
    to: config.to.join(', '),
    cc: config.cc?.join(', ') ?? '',
    subject: config.subject,
    text: config.text ?? '',
    html: config.html ?? '',
  };
}

export function stepSummary(step: Step): string {
  switch (step.jobType) {
    case 'HTTP': {
      const config = step.config as HttpStepConfig;
      return `${config.method} ${config.url}`;
    }
    case 'DELAY':
      return `Wait ${(step.config as DelayStepConfig).duration}`;
    case 'TRANSFORM': {
      const expression = (step.config as TransformStepConfig).expression.replace(/\s+/g, ' ');
      return `JSONata: ${expression.length > 80 ? expression.slice(0, 77) + '…' : expression}`;
    }
    case 'EMAIL': {
      const config = step.config as EmailStepConfig;
      return `Email to ${config.to.join(', ')}: ${config.subject}`;
    }
  }
}

function splitAddresses(value: string): string[] {
  return value
    .split(/[,;\n]/)
    .map((address) => address.trim())
    .filter((address) => address.length > 0);
}

export function jsonValidator(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '').trim();
  if (!value) {
    return null;
  }
  try {
    JSON.parse(value);
    return null;
  } catch {
    return { json: true };
  }
}

export function jsonObjectValidator(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '').trim();
  if (!value) {
    return null;
  }
  try {
    const parsed = JSON.parse(value);
    const isObject = typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed);
    return isObject ? null : { jsonObject: true };
  } catch {
    return { jsonObject: true };
  }
}
