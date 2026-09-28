export function safeReturnUrl(returnUrl: string | null): string {
  return returnUrl &&
    returnUrl.startsWith('/') &&
    !returnUrl.startsWith('//') &&
    !returnUrl.startsWith('/login')
    ? returnUrl
    : '/dashboard';
}
