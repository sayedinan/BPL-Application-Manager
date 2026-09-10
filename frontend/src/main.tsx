// Application entry point. Mounts the root component, wires up React
// Query, and provides the router. Feature routes will be added inside
// App.tsx as the corresponding feature packages land — see SPEC §9.2.

import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from '@/app/App';
import '@/styles/index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
