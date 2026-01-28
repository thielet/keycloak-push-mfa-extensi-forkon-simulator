import { onReady } from '../shared.js';
import { initializeSseListener } from '../util/sse-util.js';

onReady(() => {
  // Page loaded
  initializeSseListener();
});
