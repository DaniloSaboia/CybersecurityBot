// Run before the real-stack build. This validates only variable presence and
// never emits credential, token, query, or fixture values.

import { assertDisposableCredentials } from './real-stack-auth.js';
import { assertRealStackBuildEnvironment, assertRealStackFixtures } from './real-stack-contract.js';

assertRealStackBuildEnvironment();
assertDisposableCredentials();
assertRealStackFixtures();
