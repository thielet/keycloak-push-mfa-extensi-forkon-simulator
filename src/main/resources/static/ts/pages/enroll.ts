import { getById, onReady } from '../shared.js';
import { createNewKeyPair } from '../util/keys-util.js';
import {
  getCredentialId,
  createEnrollmentJwt,
  unpackEnrollmentToken,
  requestDpopAccessToken,
  createDpopProof,
} from '../util/token-util.js';
import { postEnrollComplete } from '../util/http-util.js';
import { initializeSseListener } from '../util/sse-util.js';
import { ENROLL_COMPLETE } from '../util/urls.js';

onReady(() => {
  const qs = new URLSearchParams(location.search);

  // query parameter fields
  const tokenEl = getById<HTMLInputElement>('token');
  const contextEl = getById<HTMLInputElement>('context');
  const iamUrlEl = getById<HTMLInputElement>('iam-url');
  const providerTypeEl = getById<HTMLInputElement>('provider-type');
  const callTypeEl = getById<HTMLSelectElement>('callType');
  const dpopEl = getById<HTMLInputElement>('dpop');
  const outEl = getById<HTMLInputElement>('out');

  // actions
  const createJwkBtn = getById<HTMLInputElement>('createJwkBtn');
  const enrollBtn = getById<HTMLInputElement>('enrollBtn');

  const collectTokenFromParamOrUri = () => {
    // direct token param or token from request_uri param
    if (qs.has('request_uri')) {
      const uri = qs.get('request_uri');
      if (!uri) {
        outEl.textContent = 'request_uri parameter is empty.';
        return;
      }
      fetch(uri, { method: 'GET', headers: { Accept: 'application/jwt' } })
        .then((response) => {
          if (!response.ok) {
            throw new Error('Failed to fetch token from request_uri');
          }
          return response.text();
        })
        .then((tokenText) => {
          tokenEl.value = tokenText;
          tokenEl.dispatchEvent(new Event('change')); // trigger change event to update iamUrl
        })
        .catch((e) => {
          console.error('Error fetching token from request_uri:', e);
          outEl.textContent = 'Error fetching token from request_uri: ' + e.message;
        });
    } else if (qs.has('token')) {
      tokenEl.value = qs.get('token') ?? '';
    }
  };

  // Function to extract issuer from token and set iamUrl
  const updateIamUrlFromToken = () => {
    const token = tokenEl.value.trim();
    if (token) {
      try {
        const enrollmentValues = unpackEnrollmentToken(token);
        if (enrollmentValues?.iss) {
          const url = enrollmentValues.iss;
          // Replace "localhost" with the configured replacement if necessary
          if (url.includes("localhost") && window.ENV.localhostReplacement) {
            iamUrlEl.value = url.replace(/localhost/g, window.ENV.localhostReplacement);
          } else {
            iamUrlEl.value = enrollmentValues.iss;
          }
        }
      } catch (e) {
        console.error('Error parsing token for issuer:', e);
      }
    }
  };

  // collect token either from token param or by requesting request_uri param on page load
  collectTokenFromParamOrUri();

  // Extract issuer from token on page load
  updateIamUrlFromToken();

  // Fill provider type options from server-provided list
  const providerIds = window.ENV.providerIds || "[]";
  providerIds.forEach((providerId) => {
    const option = document.createElement('option');
    option.value = providerId;
    option.textContent = providerId;
    providerTypeEl.appendChild(option);
  });

  // Update iamUrl when token is changed
  tokenEl.addEventListener('change', updateIamUrlFromToken);
  tokenEl.addEventListener('input', updateIamUrlFromToken);

  createJwkBtn.addEventListener('click', async () => {
    await createNewKeyPair();
  });

  enrollBtn.addEventListener('click', async () => {
    const callType = callTypeEl.value.trim();
    const _token = tokenEl.value.trim();
    const _context = contextEl.value.trim();
    let _iamUrl: string | URL = iamUrlEl.value.trim();
    if (!_token) {
      outEl.textContent = 'Please enter token.';
      return;
    }
    if (_iamUrl) {
      try {
        _iamUrl = new URL(_iamUrl);
      } catch (e) {
        console.error('Error parsing IAM URL:', e);
        outEl.textContent = 'Not a valid url.';
        return;
      }
    }

    // Backend flow
    if (callType === 'backend') {
      outEl.textContent = 'Starting backend enrollment...';
      try {
        const formData = new FormData();
        formData.append('token', _token);
        if (_context) formData.append('context', _context);
        formData.append(
          'iamUrl',
          _iamUrl ? _iamUrl.toString() : 'http://localhost:8080/realms/demo'
        );
        formData.append('pushProviderType', providerTypeEl.value.trim() || 'log');
        formData.append('dpop', dpopEl.checked ? 'true' : 'false');

        const response = await fetch('./enroll/complete', {
          method: 'POST',
          body: formData,
        });

        if (!response.ok) {
          const error = await response.text();
          outEl.textContent = 'Error: ' + error;
          return;
        }
        const data = await response.text();
        outEl.textContent = data;
      } catch (e) {
        outEl.textContent = 'Error: ' + (e instanceof Error ? e.message : String(e));
      }
      return;
    }

    // Frontend flow
    outEl.textContent = 'Starting enrollment...';
    try {
      const enrollmentValues = unpackEnrollmentToken(_token);
      if (enrollmentValues === null) {
        outEl.textContent = 'invalid enrollment token payload';
        return;
      }
      const enrollmentJwt = await createEnrollmentJwt(
        enrollmentValues,
        _context,
        providerTypeEl.value.trim() || 'log'
      );
      let accessToken = undefined;
      let dPop = undefined;
      if (dpopEl.checked) {
        const credentialId = getCredentialId(enrollmentValues.userId, _context);
        accessToken = await requestDpopAccessToken(credentialId, _iamUrl.toString());
        if (!accessToken) {
          outEl.textContent = 'Failed to obtain DPoP access token.';
          return;
        }
        dPop = await createDpopProof(
          credentialId,
          'POST',
          _iamUrl.toString() + ENROLL_COMPLETE,
          accessToken
        );
      }
      const keycloakResponse = await postEnrollComplete(
        enrollmentJwt,
        _iamUrl as URL,
        accessToken,
        dPop
      );

      if (!keycloakResponse.ok) {
        const keycloakError = await keycloakResponse.text();
        outEl.textContent = 'KeycloakError: ' + keycloakError;
        return;
      }
      const data = await keycloakResponse.text();
      outEl.textContent = JSON.stringify(data, null, 2);
    } catch (e) {
      outEl.textContent = 'Error: ' + (e instanceof Error ? e.message : String(e));
    }
  });
  initializeSseListener();
});
