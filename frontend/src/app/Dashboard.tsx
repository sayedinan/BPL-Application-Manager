export function DashboardPlaceholder() {
  // §9.3 cards + §6.1 lock: disabled/spinner during STARTING/STOPPING/ERROR; RUNNING shows status
  return (
    <div>
      <h2>Dashboard</h2>
      <div className="grid">{/* cards: name, status badge (RUNNING/STOPPED/STARTING/STOPPING/ERROR), start/stop buttons disabled during STARTING/STOPPING */}</div>
    </div>
  );
}
