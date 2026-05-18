export {};

declare global {
  interface Window {
    ENV: {
      clientId: string;
      clientSecret: string;
      sseFlag: string;
      providerIds: string[];
      localhostReplacement: string;
    };
  }
}
