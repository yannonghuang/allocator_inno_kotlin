import { redirect } from 'next/navigation';

export default function CaseIndexPage({ params }: { params: { id: string } }) {
  redirect(`/cases/${params.id}/planning`);
}
