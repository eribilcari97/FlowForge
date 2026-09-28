import { AbstractControl, ValidationErrors } from '@angular/forms';

import { DelayStepConfig, HttpMethod, HttpStepConfig, Step } from './workflow.service';

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

export const emptyDelayConfig: DelayConfigForm = { duration: 'PT30S' };

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

export function stepSummary(step: Step): string {
  if (step.jobType === 'HTTP') {
    const config = step.config as HttpStepConfig;
    return `${config.method} ${config.url}`;
  }
  return `Wait ${(step.config as DelayStepConfig).duration}`;
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
