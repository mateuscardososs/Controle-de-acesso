// Alias visual temporário: Front 2 exibido como Front 3 sem alterar backend.
const FRONT_2_INTERNAL_NAME = "Front 2";
const FRONT_2_DISPLAY_NAME = "Front 3";

export function displayAreaName(name?: string | null) {
  if (!name) return "";
  return name === FRONT_2_INTERNAL_NAME ? FRONT_2_DISPLAY_NAME : name;
}

export function displayAreaText(value?: string | null) {
  if (!value) return "";
  return value.replace(/\bFront 2\b/g, FRONT_2_DISPLAY_NAME);
}

export function displayAreaList(names?: string[] | null, separator = " / ") {
  if (!names || names.length === 0) return "";
  return names.map((name) => displayAreaName(name)).join(separator);
}
