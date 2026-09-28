import { HttpErrorResponse } from '@angular/common/http';

export interface Problem {
  status: number;
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
  problems?: { stepKey: string | null; message: string }[];
  cycle?: string[];
}

export function problemOf(error: unknown): Problem | null {
  if (
    error instanceof HttpErrorResponse &&
    error.error &&
    typeof error.error === 'object' &&
    'status' in error.error
  ) {
    return error.error as Problem;
  }
  return null;
}
