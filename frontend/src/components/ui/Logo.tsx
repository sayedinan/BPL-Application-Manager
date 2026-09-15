export function Logo({ className = 'h-8 w-8' }: { className?: string }): JSX.Element {
  return (
    <>
      <img src="/logo.png" alt="BPL" className={`${className} dark:hidden`} />
      <img src="/logo2.png" alt="BPL" className={`${className} hidden dark:block`} />
    </>
  );
}
