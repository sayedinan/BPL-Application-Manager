import { useCallback, useId, type ReactNode } from 'react';
import { Dialog, DialogBackdrop, DialogPanel } from '@headlessui/react';

interface ModalProps {
  onClose: () => void;
  children: ReactNode;
  widthClass?: string;
}

const FIRST_FIELD = 'input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled])';

/**
 * Accessible modal built on Headless UI's Dialog: focus is trapped inside while
 * open and restored to the trigger on close, Escape and backdrop click call
 * onClose, and the page behind is inert.
 *
 * Callers keep rendering their own heading. Once the panel mounts, the modal
 * finds the first heading and wires it up as the dialog's accessible name
 * (aria-labelledby), then moves focus to the first form field when there is one.
 */
export function Modal({ onClose, children, widthClass = 'max-w-md' }: ModalProps): JSX.Element {
  const fallbackId = useId();

  // A callback ref runs when the panel actually mounts (the Dialog renders in a
  // portal, so a useEffect on the parent can run before the panel exists).
  const wirePanel = useCallback(
    (panel: HTMLDivElement | null) => {
      if (!panel) return;

      // Headless UI puts role="dialog" on the wrapper around the panel, so the
      // label goes there, where assistive tech looks for it.
      const dialog = panel.closest<HTMLElement>('[role="dialog"]') ?? panel;
      const heading = panel.querySelector<HTMLElement>('h1, h2, h3');
      if (heading) {
        if (!heading.id) heading.id = `${fallbackId}-title`;
        dialog.setAttribute('aria-labelledby', heading.id);
      }

      // Land on the first field, not the close button or the wrapper. Deferred a
      // frame so it runs after Headless UI's own initial-focus step.
      const firstField = panel.querySelector<HTMLElement>(FIRST_FIELD);
      if (firstField) {
        requestAnimationFrame(() => {
          if (panel.isConnected && !panel.querySelector(':focus')) firstField.focus();
        });
      }
    },
    [fallbackId],
  );

  return (
    <Dialog open onClose={onClose} className="relative z-40">
      <DialogBackdrop className="fixed inset-0 bg-ink/50 backdrop-blur-[2px]" />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          ref={wirePanel}
          className={`relative max-h-[90vh] w-full ${widthClass} animate-fade-in overflow-y-auto rounded-xl border border-rule bg-panel shadow-popover`}
        >
          {children}
          {/* Rendered after the content so the first focusable element is the
              form's first field, not the close button. Positioned over the corner. */}
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="absolute right-3 top-2.5 inline-flex h-8 w-8 items-center justify-center rounded-lg text-muted hover:bg-sunken hover:text-ink"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
