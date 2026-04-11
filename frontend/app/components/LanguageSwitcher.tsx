'use client';

import { useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale } from 'next-intl';

export function LanguageSwitcher() {
  const router = useRouter();
  const locale = useLocale();
  const [isPending, startTransition] = useTransition();

  const switchTo = (next: string) => {
    document.cookie = `NEXT_LOCALE=${next}; path=/; max-age=31536000; SameSite=Lax`;
    startTransition(() => {
      router.refresh();
    });
  };

  return (
    <div style={{ position: 'fixed', top: 8, right: 16, zIndex: 1000, display: 'flex', gap: 4 }}>
      <button
        className={locale === 'en' ? '' : 'secondary'}
        style={{ fontSize: '0.78em', padding: '2px 8px', minWidth: 36 }}
        onClick={() => switchTo('en')}
        disabled={isPending || locale === 'en'}
        title="Switch to English"
      >
        EN
      </button>
      <button
        className={locale === 'zh' ? '' : 'secondary'}
        style={{ fontSize: '0.78em', padding: '2px 8px', minWidth: 36 }}
        onClick={() => switchTo('zh')}
        disabled={isPending || locale === 'zh'}
        title="切换到中文"
      >
        中文
      </button>
    </div>
  );
}
