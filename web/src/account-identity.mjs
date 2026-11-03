const present = (value) => typeof value === "string" && value.trim() || null;

/** Only human-readable identity fields belong in account labels. */
export const accountName = (user) =>
  present(user?.displayName) || present(user?.username) || present(user?.email);

export const accountLogin = (user) => present(user?.username) || present(user?.email);
