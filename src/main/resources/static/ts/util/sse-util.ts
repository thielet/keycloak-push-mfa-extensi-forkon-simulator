import { unpackLoginConfirmToken } from './token-util';

let sseEmitter: EventSource;

export type FcmMessage = {
  token: string;
  notification: {
    title: string;
    body: string;
  };
  data: {
    token: string;
  };
};

export function initializeSseListener(): void {
  sseEmitter = new EventSource('./fcm/register-sse', {
    withCredentials: true,
  });
  sseEmitter.onopen = function () {
    console.log('SSE connection opened.');
  };
  sseEmitter.onerror = function (error) {
    console.error('SSE error:', error);
  };
  sseEmitter.addEventListener('fcm-message', handleMessage);
  sseEmitter.addEventListener('heartbeat', function () {
    console.debug('SSE heartbeat received');
  });
}

const handleMessage = (event: MessageEvent) => {
  console.debug('SSE message received:', event.data);
  const data: FcmMessage = JSON.parse(event.data);

  const notification = document.createElement('div');
  notification.setAttribute('class', 'card');
  notification.style.cursor = 'pointer';

  const notificationHeader = document.createElement('div');
  notificationHeader.setAttribute('class', 'card-header');
  const headerContent = document.createElement('h6');
  headerContent.innerText = data.notification.title;
  notificationHeader.style.backgroundColor = '#82c5e9';
  notificationHeader.style.height = '2.0em';
  notificationHeader.style.paddingTop = '0.3em';
  notificationHeader.appendChild(headerContent);

  const unpackedToken = unpackLoginConfirmToken(data.data.token);

  const notificationBody = document.createElement('div');
  notificationBody.setAttribute('class', 'card-body');
  const bodyContent = document.createElement('p');
  bodyContent.innerText = data.notification.body + '\nCredential ID: ' + unpackedToken?.credId;
  notificationBody.appendChild(bodyContent);
  notificationBody.style.height = '4.0em';
  notificationBody.style.paddingTop = '0.4em';

  notification.appendChild(notificationHeader);
  notification.appendChild(notificationBody);

  notification.addEventListener('click', function () {
    onNotificationClicked(data.data.token, notification);
  });

  const pushMessagesContainer = document.getElementById('push-messages');
  if (pushMessagesContainer) {
    pushMessagesContainer.appendChild(notification);
  }
};

export function onNotificationClicked(token: string, notification: Element) {
  const pushMessagesContainer = document.getElementById('push-messages');
  if (pushMessagesContainer) {
    pushMessagesContainer.removeChild(notification);
  }
  // const path = document.getElementById('basepath')?.getAttribute('value') || '';
  const path = window.ENV.basepath || '';
  window.location.href = path + 'confirm?token=' + token;
}
