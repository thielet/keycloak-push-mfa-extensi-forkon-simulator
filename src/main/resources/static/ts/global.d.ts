export {};

declare global {
  interface Window {
    ENV: {
      clientId: string;
      clientSecret: string;
      basepath: string;
    };
  }
}
