import { decodeJwt, importJWK, JWTHeaderParameters, JWTPayload, SignJWT } from 'jose';
import type { JWK, KeyLike } from 'jose';
import { v4 as uuidv4 } from 'uuid';

const DPOP_HEADER_TYPE = 'dpop+jwt';
const JWT_HEADER_TYPE = 'JWT';
const ALG_RS256 = 'RS256';
const KID = 'DEVICE_KEY_ID';
const DEVICE_KEY_SUFFIX = 'device-key-';
const DEVICE_STATIC_ID = `device-static-id`;
const DEVICE_ALIAS = '-device-alias-';
const DEVICE_TYPE = 'ios';
const PUSH_PROVIDER_ID = 'demo-push-provider-token';
const PUSH_PROVIDER_TYPE = 'log';
const DEVICE_LABEL = 'Demo Phone';

export type DpopPayload = {
  cid?: string;
  htm?: string;
  htu?: string;
  sub?: string;
  deviceId?: string;
  credId?: string;
  action?: string;
  userVerification?: string;
};

export type EnrollmentValues = {
  enrollmentId: string;
  nonce: string;
  userId: string;
  iss?: string;
};

export type ConfirmLoginValues = {
  challengeId: string;
  credId: string;
  userVerification?: string;
  iss?: string;
};

export function unpackEnrollmentToken(token: string): EnrollmentValues | null {
  const enrollPayload = decodeJwt(token) as Record<string, unknown>;
  const enrollmentId = enrollPayload.enrollmentId as string | undefined;
  const nonce = enrollPayload.nonce as string | undefined;
  const userId = enrollPayload.sub as string | undefined;
  const iss = enrollPayload.iss as string | undefined;

  if (!enrollmentId || !nonce || !userId) {
    return null;
  }
  return { enrollmentId, nonce, userId, iss };
}

export async function createEnrollmentJwt(
  enrollmentValues: EnrollmentValues,
  context: string,
  providerType: string
) {
  const exp = Math.floor(Date.now() / 1000) + 300;
  const { privateKey, jwkPub } = await loadJwkFile();

  const deviceKeyId = `${DEVICE_KEY_SUFFIX}${uuidv4()}`;

  const credentialId = getCredentialId(enrollmentValues.userId, context);
  const protectedHeader = {
    alg: ALG_RS256,
    kid: deviceKeyId,
    typ: JWT_HEADER_TYPE,
  };

  jwkPub.kid = deviceKeyId;
  const cnf = { jwk: jwkPub };

  const jwtPayload = {
    enrollmentId: enrollmentValues.enrollmentId,
    nonce: enrollmentValues.nonce,
    sub: enrollmentValues.userId,
    deviceType: DEVICE_TYPE,
    pushProviderId: PUSH_PROVIDER_ID,
    pushProviderType: providerType ? providerType : PUSH_PROVIDER_TYPE,
    credentialId: credentialId,
    deviceId: DEVICE_STATIC_ID,
    deviceLabel: DEVICE_LABEL,
    cnf,
  };
  return await signJwt(jwtPayload, protectedHeader, exp, privateKey);
}

export function unpackLoginConfirmToken(token: string): ConfirmLoginValues | null {
  const confirmPayload = decodeJwt(token) as Record<string, unknown>;

  const challengeId = confirmPayload.cid as string | undefined;
  const credId = confirmPayload.credId as string | undefined;
  const iss = confirmPayload.iss as string | undefined;

  if (!challengeId || !credId) {
    return null;
  }
  return { challengeId, credId, iss };
}

export function extractUserIdFromCredentialId(credentialId: string): string | null {
  if (!credentialId) {
    return null;
  }

  const aliasIndex = credentialId.indexOf(DEVICE_ALIAS);
  if (aliasIndex < 0) {
    return null;
  }
  const userId = credentialId.slice(0, aliasIndex);
  return userId.length > 0 ? userId : null;
}

export async function createDpopProof(credentialId: string, method: string, htu: string) {
  const userId = extractUserIdFromCredentialId(credentialId) ?? credentialId;

  const dpopTokenPayload: DpopPayload = {
    htm: method,
    htu: htu,
    sub: userId,
    deviceId: DEVICE_STATIC_ID,
  };

  return await createDpopJwt(dpopTokenPayload);
}

export async function createConfirmJwt(payload: DpopPayload) {
  const exp = Math.floor(Date.now() / 1000) + 300;
  const { privateKey } = await loadJwkFile();

  const protectedHeader = { alg: ALG_RS256, kid: KID, typ: JWT_HEADER_TYPE };

  return await signJwt(payload, protectedHeader, exp, privateKey);
}

export async function createAccessToken(userId: string, htu: string) {
  const ctxEndIndex = userId?.indexOf(DEVICE_ALIAS);
  const _aliasAndEkid = userId.substring(ctxEndIndex, userId.length);
  const ekid = _aliasAndEkid?.slice(DEVICE_ALIAS.length) as string;

  const dpopTokenPayload: DpopPayload = {
    htm: 'POST',
    htu: htu,
    sub: ekid,
    deviceId: DEVICE_STATIC_ID,
  };

  return await createDpopJwt(dpopTokenPayload);
}

export async function createChallengeToken(
  userId: string,
  challengeId: string,
  action: string = 'approve',
  userVerification?: string
) {
  const body: DpopPayload = {
    cid: challengeId,
    credId: userId,
    deviceId: DEVICE_STATIC_ID,
    action: action,
  };
  if (userVerification && userVerification.trim().length > 0) {
    body.userVerification = userVerification;
  }
  return await createConfirmJwt(body);
}

export function getCredentialId(userId: string, context: string) {
  return `${userId}${DEVICE_ALIAS}${context}`;
}

async function createDpopJwt(dpopPayload: DpopPayload) {
  const { privateKey, jwkPub } = await loadJwkFile();
  return await signDpopJwt(
    dpopPayload,
    { alg: ALG_RS256, typ: DPOP_HEADER_TYPE, jwk: jwkPub },
    uuidv4(),
    privateKey
  );
}

export type JwkBundle = {
  public: JWK;
  private: JWK;
};

export async function loadJwkFile() {
  const res = await fetch(`keys/rsa-jwk.json`, {
    cache: 'no-store',
  });

  if (!res.ok) {
    throw new Error(`Could not load rsa-jwk: ${res.status}`);
  }

  const jwk = (await res.json()) as JwkBundle;

  try {
    // Jose v5 requires Web Crypto API - ensure it's available
    if (typeof globalThis === 'undefined' || !globalThis.crypto) {
      throw new Error('Web Crypto API not available');
    }

    // Pass both JWK and algorithm options
    const publicKey = await importJWK(jwk.public as JWK, ALG_RS256);
    const privateKey = await importJWK(jwk.private as JWK, ALG_RS256);
    const jwkPub = jwk.public;

    return { privateKey, publicKey, jwkPub };
  } catch (error) {
    console.error('Error importing JWK:', error);
    console.error('Public JWK:', jwk.public);
    console.error('Private JWK:', jwk.private);
    console.error(
      'Web Crypto available:',
      typeof globalThis !== 'undefined' && !!globalThis.crypto
    );
    throw new Error(
      `Failed to import JWK: ${error instanceof Error ? error.message : String(error)}`
    );
  }
}

async function signDpopJwt(
  payload: JWTPayload,
  protectedHeader: JWTHeaderParameters,
  jti: string,
  privateKey: KeyLike | Uint8Array | JWK
) {
  return await new SignJWT(payload)
    .setProtectedHeader(protectedHeader)
    .setIssuedAt()
    .setJti(jti)
    .sign(privateKey);
}

async function signJwt(
  payload: JWTPayload,
  protectedHeader: JWTHeaderParameters,
  exp: number | string | Date,
  privateKey: KeyLike | Uint8Array | JWK
) {
  return await new SignJWT(payload)
    .setProtectedHeader(protectedHeader)
    .setExpirationTime(exp)
    .sign(privateKey);
}
