import { useAuth } from '@/auth/AuthContext';
import { LogsBox } from '@/app/LogsBox';
import { Card, PageHeader } from '@/components/ui/Card';

export function LogsPage(): JSX.Element {
  const { user } = useAuth();

  return (
    <div className="mx-auto max-w-6xl p-6 sm:p-8">
      <PageHeader title="Logs" description="Audit log and per-application logs" />

      <Card className="overflow-hidden">
        <LogsBox role={user?.role} />
      </Card>
    </div>
  );
}