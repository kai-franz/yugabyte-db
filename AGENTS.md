## Build System

The primary build entry point is `yb_build.sh` at the repository root.

### Common Build Commands

```bash
# Compile-only
./yb_build.sh release
```

### C++ Tests

```bash
# Run a specific C++ test
./yb_build.sh release --cxx-test tablet-test

# Run a specific sub-test (gtest filter)
./yb_build.sh release --cxx-test cluster_balance_preferred_leader-test --gtest_filter TestLoadBalancerPreferredLeader.TestBalancingMultiPriorityWildcardLeaderPreference

# Run all C++ tests matching regex filter
./yb_build.sh release --cxx-test cluster_balance_preferred_leader-test --gtest_filter "*Wildcard*"

# Run a test multiple times (for flakiness detection)
./yb_build.sh release --cxx-test cluster_balance_preferred_leader-test -n 10
```

### Java Tests

```bash
# Run a specific Java test
./yb_build.sh release --java-test org.yb.client.TestYBClient

# Run a specific sub-test
./yb_build.sh release --java-test 'org.yb.client.TestYBClient#testClientCreateDestroy'
```

If a test fails during initdb, run reinitdb:
```bash
./yb_build.sh release reinitdb
```

### After making changes, run the linter and ensure there are no errors.
```bash
arc lint
```
