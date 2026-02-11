import { getById, onReady, setMessage } from '../shared.js';
import {
  unpackLoginConfirmToken,
  extractUserIdFromCredentialId,
  createChallengeToken,
  createDpopProof,
} from '../util/token-util.js';
import { TOKEN_ENDPOINT, LOGIN_PENDING_ENDPOINT, CHALLENGE_ENDPOINT } from '../util/urls.js';
import {
  postAccessToken,
  getPendingChallenges,
  postChallengesResponse,
} from '../util/http-util.js';
import { initializeSseListener } from '../util/sse-util.js';

const CHALLENGE_ID = 'CHALLENGE_ID';

onReady(() => {
  const qs = new URLSearchParams(location.search);

  const tokenEl = getById<HTMLInputElement>('token');
  const confirmBtnEl = getById<HTMLFormElement>('confirmBtn');
  const confirmBtnBackendEl = getById<HTMLFormElement>('confirmBtnBackend');
  const iamUrlEl = getById<HTMLInputElement>('iam-url');
  const messageEl = getById<HTMLElement>('message');
  const actionEl = getById<HTMLSelectElement>('action');
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
          iamUrlEl.value = confirmTokenValues.iss;
        }
      } catch (e) {
        // Silently ignore token parsing errors
      }
    }
  };
  // Extract issuer from token on page load
  updateIamUrlFromToken();

  // Update iamUrl when token is changed
  tokenEl.addEventListener('change', updateIamUrlFromToken);
  tokenEl.addEventListener('input', updateIamUrlFromToken);

  confirmBtnBackendEl.addEventListener('click', async (_e) => {
    const _token = tokenEl.value.trim();
    const _context = contextEl.value.trim();
    let _iamUrl: string | URL = iamUrlEl.value.trim();
    if (!_token) {
      setMessage(messageEl, 'token required...', 'error');
      return;
    }
    if (_iamUrl) {
      try {
        _iamUrl = new URL(_iamUrl);
      } catch (e) {
        setMessage(messageEl, 'Not a valid url...', 'error');
        return;
      }
    }

    setMessage(messageEl, 'Starting backend enrollment...');

    try {
      const formData = new FormData();
      formData.append('token', _token);
      if (_context) formData.append('context', _context);
      formData.append('iamUrl', _iamUrl ? _iamUrl.toString() : 'http://localhost:8080/realms/demo');

      const response = await fetch('./confirm/login', {
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
  });

  confirmBtnEl.addEventListener('click', async (e) => {
    e.preventDefault();
    setMessage(messageEl, 'Logging in...', 'info');

    try {
      const _action = actionEl.value.trim();
      const _token = tokenEl.value.trim();
      const _context = contextEl.value.trim();
      const _userVerification = userVerificationEl.value.trim();
      const _iamUrl: string | URL = iamUrlEl.value.trim();

      if (!_token) {
        setMessage(messageEl, 'token required...', 'error');
        return;
      }
      const confirmValues = unpackLoginConfirmToken(_token);
      if (confirmValues === null) {
        setMessage(messageEl, 'invalid confirm token payload...', 'error');
        return;
      }
      const effectiveAction = (_action ?? 'approve').trim().toLowerCase();
      const tokenUserVerification = confirmValues.userVerification;
      const effectiveUserVerification = firstNonBlank(
        _userVerification,
        tokenUserVerification,
        _context
      );

      const credentialId = confirmValues.credId;
      const challengeId = confirmValues.challengeId;
      const userId = extractUserIdFromCredentialId(credentialId);

      if (!userId) {
        setMessage(messageEl, 'unable to extract user id from credential id...', 'error');
        return;
      }

      const dPopAccessToken = await createDpopProof(
        credentialId,
        'POST',
        _iamUrl?.toString() + TOKEN_ENDPOINT
      );
      const accessTokenResponse = await postAccessToken(_iamUrl, dPopAccessToken);

      if (!accessTokenResponse.ok) {
        setMessage(messageEl, `${await accessTokenResponse.text()}`, 'error');
        return;
      }
      const accessTokenJson = (await accessTokenResponse.json()) as Record<string, string>;
      const accessToken = accessTokenJson['access_token'];

      const pendingUrl = new URL(_iamUrl?.toString() + LOGIN_PENDING_ENDPOINT);
      const pendingHtu = new URL(_iamUrl?.toString() + LOGIN_PENDING_ENDPOINT);
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
      const url = _iamUrl + CHALLENGE_ENDPOINT.replace(CHALLENGE_ID, challengeId);
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
        `userId: ${userId}; responseStatus: ${challengeResponse.status}; userVerification: ${pendingUserVerification}; `,
        'success'
      );
    } catch (e) {
      setMessage(messageEl, 'Error: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
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
