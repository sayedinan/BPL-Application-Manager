import { BarChart, Bar, XAxis, YAxis, Cell, ResponsiveContainer } from 'recharts';

interface UptimeBarChartProps {
  data: { name: string; uptimePct: number }[];
}

export function UptimeBarChart({ data }: UptimeBarChartProps) {
  if (data.length === 0) return null;

  return (
    <ResponsiveContainer width="100%" height={Math.max(120, data.length * 36)}>
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 24, bottom: 4, left: 4 }}>
        <XAxis type="number" domain={[0, 100]} tickFormatter={(v) => `${v}%`} tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
        <YAxis type="category" dataKey="name" width={90} tick={{ fontSize: 12, fill: '#64748b' }} axisLine={false} tickLine={false} />
        <Bar dataKey="uptimePct" radius={[0, 6, 6, 0]} barSize={14}>
          {data.map((entry) => (
            <Cell key={entry.name} fill={entry.uptimePct >= 90 ? '#16a34a' : entry.uptimePct >= 60 ? '#d97706' : '#dc2626'} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
