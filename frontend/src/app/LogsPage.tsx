import { useAuth } from '@/auth/AuthContext';
import { LogsBox } from '@/app/LogsBox';
import { Card, PageHeader } from '@/components/ui/Card';

export function LogsPage(): JSX.Element {
  const { user } = useAuth();

  return (
    <div className="mx-auto max-w-6xl px-4 py-6 sm:p-8">
      <PageHeader
        title="Logs"
        description="Who did what, and what each application is writing. Updates live."
      />

      <Card className="overflow-hidden">
        <LogsBox role={user?.role} />
      </Card>
    </div>
  );
}
