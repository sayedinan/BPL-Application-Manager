import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SummaryTiles, type Filter } from './SummaryTiles';

const counts: Record<Filter, number> = { all: 5, attention: 2, healthy: 3, maintenance: 0 };
const tiles = () => within(screen.getByRole('group', { name: 'Filter applications' })).getAllByRole('button');

afterEach(cleanup);

describe('SummaryTiles', () => {
  it('shows four toggle buttons with their counts, marking the active one', () => {
    render(<SummaryTiles counts={counts} active="attention" onSelect={() => {}} />);
    expect(tiles().map((b) => b.textContent)).toEqual([
      'Applications5',
      'Healthy3',
      'Need attention2',
      'Maintenance0',
    ]);
    expect(tiles().map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'true', 'false']);
  });

  it('selects an inactive tile', () => {
    const onSelect = vi.fn();
    render(<SummaryTiles counts={counts} active="all" onSelect={onSelect} />);
    fireEvent.click(tiles()[1]);
    expect(onSelect).toHaveBeenCalledWith('healthy');
  });

  it('goes back to all when the active tile is clicked again', () => {
    const onSelect = vi.fn();
    render(<SummaryTiles counts={counts} active="healthy" onSelect={onSelect} />);
    fireEvent.click(tiles()[1]);
    expect(onSelect).toHaveBeenCalledWith('all');
  });
});
