import { ActiveCaseMarker } from '@/app/components/ActiveCaseMarker';

export default function CaseLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: { id: string };
}) {
  return (
    <>
      <ActiveCaseMarker caseId={Number(params.id)} />
      {children}
    </>
  );
}
