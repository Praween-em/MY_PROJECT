import { CommonActions, createNavigationContainerRef } from '@react-navigation/native';

/** Root stack ref so login, home, and plans can move even during a transition. */
export const navigationRef = createNavigationContainerRef();

function rootReset(screen, params) {
  return CommonActions.reset({
    index: 0,
    routes: [{ name: screen, params }],
  });
}

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
  if (navigationRef.isReady()) {
    navigationRef.navigate(screen, params);
    return;
  }
  getRootNavigation(navigation)?.navigate?.(screen, params);
}

export function replaceRoot(navigation, screen, params) {
  const action = rootReset(screen, params);
  if (navigationRef.isReady()) {
    navigationRef.dispatch(action);
    return;
  }
  getRootNavigation(navigation)?.dispatch?.(action);
}
