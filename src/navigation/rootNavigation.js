/**
 * Navigate to a screen on the root stack from inside a nested tab screen.
 */
export function getRootNavigation(navigation) {
  let nav = navigation;
  while (nav?.getParent?.()) {
    nav = nav.getParent();
  }
  return nav;
}

export function navigateRoot(navigation, screen, params) {
  getRootNavigation(navigation).navigate(screen, params);
}

export function replaceRoot(navigation, screen, params) {
  getRootNavigation(navigation).replace(screen, params);
}
