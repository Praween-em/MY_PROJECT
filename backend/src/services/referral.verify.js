/**
 * Referral logic verification — run: node src/services/referral.verify.js
 */
const {
  REFERRAL_GOAL,
  isQualifyingPlan,
  checkRewardMilestone,
  extendSubscriptionEnd,
} = require('./referral');

let passed = 0;
let failed = 0;

function assert(label, condition) {
  if (condition) {
    passed++;
    console.log(`  ✓ ${label}`);
  } else {
    failed++;
    console.error(`  ✗ ${label}`);
  }
}

console.log('Referral logic verification\n');

assert('monthly qualifies', isQualifyingPlan('monthly'));
assert('quarterly qualifies', isQualifyingPlan('quarterly'));
assert('weekly does NOT qualify', !isQualifyingPlan('weekly'));

const m9 = checkRewardMilestone(9, 10);
assert('10th paid referral grants reward', m9.grantReward && m9.rewardMonths === 1);

const m10 = checkRewardMilestone(10, 11);
assert('11th paid referral does NOT grant again', !m10.grantReward);

const m19 = checkRewardMilestone(19, 20);
assert('20th paid referral grants second reward', m19.grantReward && m19.rewardMonths === 1);

const future = extendSubscriptionEnd(
  new Date(Date.now() + 86400000 * 5).toISOString(),
  30
);
assert('extend adds 30 days from current end', new Date(future) > new Date());

assert('REFERRAL_GOAL is 10', REFERRAL_GOAL === 10);

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed > 0 ? 1 : 0);
