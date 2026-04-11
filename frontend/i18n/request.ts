import { getRequestConfig } from 'next-intl/server';
import { cookies } from 'next/headers';

export default getRequestConfig(async () => {
  const cookieStore = await cookies();
  const raw = cookieStore.get('NEXT_LOCALE')?.value ?? 'en';
  const locale = ['en', 'zh'].includes(raw) ? raw : 'en';
  return {
    locale,
    messages: (await import(`../messages/${locale}.json`)).default,
  };
});
