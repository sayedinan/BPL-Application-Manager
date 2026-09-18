import { PieChart, Pie, Cell } from 'recharts';

interface UptimeDonutProps {
  uptimeSeconds: number;
  downtimeSeconds: number;
  size?: number;
}

export function UptimeDonut({ uptimeSeconds, downtimeSeconds, size = 72 }: UptimeDonutProps) {
  const total = uptimeSeconds + downtimeSeconds;
  const pct = total > 0 ? Math.round((uptimeSeconds / total) * 100) : 0;
  const data = total > 0
    ? [
        { name: 'Uptime', value: uptimeSeconds },
        { name: 'Downtime', value: downtimeSeconds },
      ]
    : [{ name: 'No data', value: 1 }];
  const colors = total > 0 ? ['#16a34a', '#6b7280'] : ['#e2e8f0'];

  return (
    <div className="relative flex-shrink-0" style={{ width: size, height: size }}>
      <PieChart width={size} height={size}>
        <Pie
          data={data}
          dataKey="value"
          innerRadius={size / 2 - 10}
          outerRadius={size / 2}
          startAngle={90}
          endAngle={-270}
          stroke="none"
        >
          {data.map((entry, i) => (
            <Cell key={entry.name} fill={colors[i % colors.length]} />
          ))}
        </Pie>
      </PieChart>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-[11px] font-semibold text-slate-700 dark:text-slate-200">
          {total > 0 ? `${pct}%` : '—'}
        </span>
      </div>
    </div>
  );
}
