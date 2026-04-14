import type { Metadata } from 'next';
import './globals.css';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, getLocale } from 'next-intl/server';
import { SidebarLayout } from '@/app/components/SidebarLayout';

export const metadata: Metadata = {
  title: 'Supply-Demand Allocator',
  description: 'Allocate supplies to competing demands',
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await getLocale();
  const messages = await getMessages();
  return (
    <html lang={locale}>
      <body>
        <NextIntlClientProvider messages={messages}>
          <SidebarLayout>
            {children}
          </SidebarLayout>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
