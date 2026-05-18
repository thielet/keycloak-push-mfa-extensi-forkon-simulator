import { getById, onReady, setMessage } from '../shared.js';
import {
  unpackLoginConfirmToken,
  extractUserIdFromCredentialId,
  createChallengeToken,
  createDpopProof,
  requestDpopAccessToken,
} from '../util/token-util.js';
import { LOGIN_PENDING_ENDPOINT, CHALLENGE_ENDPOINT } from '../util/urls.js';
import { getPendingChallenges, postChallengesResponse } from '../util/http-util.js';
import { initializeSseListener } from '../util/sse-util.js';

const CHALLENGE_ID = 'CHALLENGE_ID';

onReady(() => {
  const qs = new URLSearchParams(location.search);

  const tokenEl = getById<HTMLInputElement>('token');
  const confirmBtnEl = getById<HTMLFormElement>('confirmBtn');
  const denyBtnEl = getById<HTMLFormElement>('denyBtn');
  const denyWithLockoutBtnEl = getById<HTMLFormElement>('denyWithLockoutBtn');
  const callTypeEl = getById<HTMLSelectElement>('callType');
  const iamUrlEl = getById<HTMLInputElement>('iam-url');
  const messageEl = getById<HTMLElement>('message');
  const contextEl = getById<HTMLInputElement>('context');
  const userVerificationEl = getById<HTMLInputElement>('userVerification');

  tokenEl.value = qs.get('token') ?? '';
  contextEl.value = qs.get('context') ?? '';
  userVerificationEl.value = qs.get('userVerification') ?? '';

  // Function to extract issuer from token and set iamUrl
  const updateIamUrlFromToken = () => {
    const token = tokenEl.value.trim();
    if (token) {
      try {
        const confirmTokenValues = unpackLoginConfirmToken(token);
        if (confirmTokenValues?.iss) {
          const url = confirmTokenValues.iss;
          // Replace "localhost" with the configured replacement if necessary
          if (url.includes("localhost") && window.ENV.localhostReplacement) {
            iamUrlEl.value = url.replace(/localhost/g, window.ENV.localhostReplacement);
          } else {
            iamUrlEl.value = confirmTokenValues.iss;
          }
        }
      } catch (e) {
        console.error('Error extracting issuer from token:', e);
        // Silently ignore token parsing errors
      }
    }
  };
  // Extract issuer from token on page load
  updateIamUrlFromToken();

  // Update iamUrl when token is changed
  tokenEl.addEventListener('change', updateIamUrlFromToken);
  tokenEl.addEventListener('input', updateIamUrlFromToken);

  // Unified handler for all three actions
  const handleAction = async (action: 'approve' | 'deny' | 'deny-lockout') => {
    const callType = callTypeEl.value.trim();
    const _token = tokenEl.value.trim();
    const _context = contextEl.value.trim();
    let _iamUrl: string | URL = iamUrlEl.value.trim();
    const _userVerification = userVerificationEl.value.trim();

    if (!_token) {
      setMessage(messageEl, 'token required...', 'error');
      return;
    }

    if (_iamUrl) {
      try {
        _iamUrl = new URL(_iamUrl);
      } catch (e) {
        console.error('Error parsing IAM URL:', e);
        setMessage(messageEl, 'Not a valid url...', 'error');
        return;
      }
    }

    // Backend flow
    if (callType === 'backend') {
      await handleBackendAction(action, _token, _context, _iamUrl);
      return;
    }

    // Frontend flow
    await handleFrontendAction(action, _token, _context, _userVerification, _iamUrl);
  };

  const handleBackendAction = async (
    action: 'approve' | 'deny' | 'deny-lockout',
    token: string,
    context: string,
    iamUrl: string | URL
  ) => {
    setMessage(messageEl, 'Starting backend action...');

    try {
      const formData = new FormData();
      formData.append('token', token);
      if (context) formData.append('context', context);
      formData.append('iamUrl', iamUrl ? iamUrl.toString() : 'http://localhost:8080/realms/demo');

      // Include action parameter for backend to handle different response types
      formData.append('action', action);

      // choose endpoint path based on lockout
      const endpoint = action === 'deny-lockout' ? './confirm/lockout' : './confirm/challenge';

      const response = await fetch(endpoint, {
        method: 'POST',
        body: formData,
      });

      if (!response.ok) {
        const error = await response.text();
        setMessage(messageEl, error, 'error');
        return;
      }
      const data = await response.text();
      setMessage(messageEl, data, 'success');
    } catch (e) {
      setMessage(messageEl, e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const handleFrontendAction = async (
    action: 'approve' | 'deny' | 'deny-lockout',
    token: string,
    context: string,
    userVerification: string,
    iamUrl: string | URL
  ) => {
    setMessage(messageEl, 'Logging in...', 'info');

    try {
      const confirmValues = unpackLoginConfirmToken(token);
      if (confirmValues === null) {
        setMessage(messageEl, 'invalid confirm token payload...', 'error');
        return;
      }

      const effectiveAction = (action === 'deny-lockout' ? 'deny' : action).trim().toLowerCase();
      const tokenUserVerification = confirmValues.userVerification;
      const effectiveUserVerification = firstNonBlank(
        userVerification,
        tokenUserVerification,
        context
      );

      const credentialId = confirmValues.credId;
      const challengeId = confirmValues.challengeId;
      const userId = extractUserIdFromCredentialId(credentialId);

      if (!userId) {
        setMessage(messageEl, 'unable to extract user id from credential id...', 'error');
        return;
      }
      const accessToken = await requestDpopAccessToken(credentialId, iamUrl?.toString());
      if (!accessToken) {
        setMessage(messageEl, 'Failed to obtain DPoP access token...', 'error');
        return;
      }

      const pendingUrl = new URL(iamUrl?.toString() + LOGIN_PENDING_ENDPOINT);
      const pendingHtu = new URL(iamUrl?.toString() + LOGIN_PENDING_ENDPOINT);
      pendingUrl.searchParams.set('userId', userId);

      // RFC 9449: htu must exclude query and fragment parts
      const pendingDpop = await createDpopProof(credentialId, 'GET', pendingHtu.toString());
      const pendingResponse = await getPendingChallenges(
        pendingUrl.toString(),
        pendingDpop,
        accessToken
      );
      if (!pendingResponse.ok) {
        setMessage(messageEl, `${await pendingResponse.text()}`, 'error');
        return;
      }
      const pendingJson = (await pendingResponse.json()) as {
        challenges?: Array<{ cid?: string; userVerification?: string }>;
      };
      const pendingChallenge =
        pendingJson?.challenges?.find((candidate) => candidate?.cid === challengeId) ?? null;
      const pendingUserVerification = pendingChallenge?.userVerification ?? null;

      if (
        effectiveAction === 'approve' &&
        pendingUserVerification != null &&
        (!effectiveUserVerification || effectiveUserVerification.trim().length === 0)
      ) {
        setMessage(messageEl, `userVerification required ...`, 'error');
        return;
      }
      const url = iamUrl + CHALLENGE_ENDPOINT.replace(CHALLENGE_ID, challengeId);
      const dpopChallengeToken = await createDpopProof(credentialId, 'POST', url);
      const challengeToken = await createChallengeToken(
        credentialId,
        challengeId,
        effectiveAction,
        effectiveAction === 'approve' ? effectiveUserVerification : undefined
      );

      const challengeResponse = await postChallengesResponse(
        url,
        dpopChallengeToken,
        accessToken,
        challengeToken
      );

      if (!challengeResponse.ok) {
        setMessage(messageEl, `${await challengeResponse.text()}`, 'error');
        return;
      }

      setMessage(
        messageEl,
        `action: ${action}; userId: ${userId}; responseStatus: ${challengeResponse.status}; userVerification: ${pendingUserVerification}; `,
        'success'
      );
    } catch (e) {
      setMessage(messageEl, 'Error: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  // Add event listeners for all three buttons
  confirmBtnEl.addEventListener('click', async (e) => {
    e.preventDefault();
    await handleAction('approve');
  });

  denyBtnEl.addEventListener('click', async (e) => {
    e.preventDefault();
    await handleAction('deny');
  });

  denyWithLockoutBtnEl.addEventListener('click', async (e) => {
    e.preventDefault();
    await handleAction('deny-lockout');
  });

  initializeSseListener();
});

const firstNonBlank = (...values: Array<string | undefined | null>) => {
  for (const value of values) {
    if (typeof value === 'string' && value.trim().length > 0) {
      return value.trim();
    }
  }
  return undefined;
};
