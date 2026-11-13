// Copyright (c) YugabyteDB, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

// How returning free memory to the OS from Google TCMalloc's page heap affects the rest of the
// process:
//
// - BM_ReleaseHoleyHugepages: how long a release holds pageheap_lock when the free memory is
//   scattered over hugepages as many small holes. TCMalloc unbacks each hole with its own
//   madvise() call.
// - BM_RaftLockConvoy: threads that allocate while holding a lock of their own, like
//   PeerMessageQueue::ResponseFromPeer under queue_lock_, wait for pageheap_lock during such a
//   release, and so stall threads that only need their lock and never allocate.
// - BM_ReuseAfterRelease: what it costs to fault memory back in when it is allocated again 100 ms
//   after it was freed and released.
// - BM_BackgroundReleaseChurn: the same cost when TCMalloc's background thread releases memory at a
//   fixed rate while the process keeps freeing and reallocating it.
//
// Build and run (use a release build). Run each benchmark in its own process: they reshape the
// process-wide heap, so one benchmark's leftovers would change the next one's results.
//   ./yb_build.sh release --cxx-benchmark tcmalloc-benchmark \
//       --benchmark-args '--benchmark_filter=BM_ReleaseHoleyHugepages'
//
// To compare two TCMalloc versions, run the same benchmark in builds that link each of them. The
// label shows whether per-CPU caches are active, the transparent hugepage mode, and TCMalloc's
// skip-subrelease intervals, all of which change the results.

#include <sys/resource.h>

#include <benchmark/benchmark.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <fstream>
#include <functional>
#include <limits>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "yb/gutil/sysinfo.h"

#include "yb/util/format.h"
#include "yb/util/locks.h"
#include "yb/util/logging.h"
#include "yb/util/size_literals.h"
#include "yb/util/status_log.h"
#include "yb/util/tcmalloc_impl_util.h"
#include "yb/util/thread.h"

#if YB_GOOGLE_TCMALLOC

namespace yb {
namespace {

using tcmalloc::MallocExtension;

using Clock = std::chrono::steady_clock;

// TCMalloc's default page size (kPageShift = 13), which YugabyteDB builds use.
constexpr size_t kTCMallocPageSize = 8_KB;
constexpr size_t kHugePageSize = 2_MB;
constexpr size_t kPagesPerHugePage = kHugePageSize / kTCMallocPageSize;
constexpr size_t kOsPageSize = 4_KB;

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
      Clock::now().time_since_epoch()).count();
}

double NsToMs(int64_t ns) {
  return ns / 1e6;
}

size_t GetProperty(const char* name) {
  return MallocExtension::GetNumericProperty(name).value_or(0);
}

// How much the page heap's unmapped memory grew since it was unmapped_before bytes.
double UnmappedMiBSince(size_t unmapped_before) {
  auto delta = static_cast<int64_t>(GetProperty("tcmalloc.pageheap_unmapped_bytes")) -
               static_cast<int64_t>(unmapped_before);
  return delta / static_cast<double>(1_MB);
}

std::string ThpMode() {
  std::ifstream file("/sys/kernel/mm/transparent_hugepage/enabled");
  std::string line;
  std::getline(file, line);
  auto begin = line.find('[');
  auto end = line.find(']');
  return begin == std::string::npos || end == std::string::npos
      ? "unknown" : line.substr(begin + 1, end - begin - 1);
}

void SetLabel(benchmark::State& state) {
  state.SetLabel(Format(
      "per_cpu=$0 thp=$1 skip_subrelease=$2/$3",
      MallocExtension::PerCpuCachesActive(), ThpMode(),
      absl::FormatDuration(MallocExtension::GetSkipSubreleaseShortInterval()),
      absl::FormatDuration(MallocExtension::GetSkipSubreleaseLongInterval())));
}

struct RusageDelta {
  explicit RusageDelta(int who) : who_(who) { getrusage(who_, &start_); }

  int64_t MinorFaults() const {
    rusage now;
    getrusage(who_, &now);
    return now.ru_minflt - start_.ru_minflt;
  }

  double CpuMs() const {
    rusage now;
    getrusage(who_, &now);
    auto ms = [](const timeval& tv) { return tv.tv_sec * 1e3 + tv.tv_usec / 1e3; };
    return ms(now.ru_utime) + ms(now.ru_stime) - ms(start_.ru_utime) - ms(start_.ru_stime);
  }

  int who_;
  rusage start_;
};

// Disables the skip-subrelease check, so that a release also returns memory that was in use a
// moment ago, as releases with zero lookbacks do (and the old MemTracker GC loop did once the
// freed memory was older than the lookbacks).
class ScopedZeroSkipSubrelease {
 public:
  ScopedZeroSkipSubrelease()
      : short_interval_(MallocExtension::GetSkipSubreleaseShortInterval()),
        long_interval_(MallocExtension::GetSkipSubreleaseLongInterval()) {
    MallocExtension::SetSkipSubreleaseShortInterval(absl::ZeroDuration());
    MallocExtension::SetSkipSubreleaseLongInterval(absl::ZeroDuration());
  }

  ~ScopedZeroSkipSubrelease() {
    MallocExtension::SetSkipSubreleaseShortInterval(short_interval_);
    MallocExtension::SetSkipSubreleaseLongInterval(long_interval_);
  }

 private:
  const absl::Duration short_interval_;
  const absl::Duration long_interval_;
};

// Moves freed objects out of this thread's and every CPU's cache, so that spans whose objects are
// all free go back to the page heap.
void FlushFrontEndCaches() {
  if (MallocExtension::PerCpuCachesActive()) {
    for (int cpu = 0; cpu <= base::MaxCPUIndex(); ++cpu) {
      MallocExtension::ReleaseCpuMemory(cpu);
    }
  }
  MallocExtension::MarkThreadIdle();
}

void ReleaseAll() {
  ScopedZeroSkipSubrelease zero_skip_subrelease;
  FlushFrontEndCaches();
  MallocExtension::ReleaseMemoryToSystem(std::numeric_limits<size_t>::max());
}

// Releases all free memory in one call (chunk_bytes = 0), or bytes in calls of chunk_bytes each,
// like MemTracker::GcTcmallocIfNeeded. Returns how long that took.
int64_t Release(size_t bytes, size_t chunk_bytes) {
  auto start = NowNs();
  if (chunk_bytes == 0) {
    MallocExtension::ReleaseMemoryToSystem(std::numeric_limits<size_t>::max());
  } else {
    for (size_t released = 0; released < bytes; released += chunk_bytes) {
      MallocExtension::ReleaseMemoryToSystem(chunk_bytes);
    }
  }
  return NowNs() - start;
}

// Fills hugepages with one-page objects, then frees runs of hole_pages pages that alternate with
// runs of hole_pages live pages. Each hugepage keeps half of its memory and has
// kPagesPerHugePage / (2 * hole_pages) free ranges; releasing it takes one madvise() per range.
class HoleyHeap {
 public:
  HoleyHeap(size_t hugepages, size_t hole_pages) {
    // Start from unbacked hugepages, so that writing to them backs them with transparent hugepages
    // as a long-running process's heap would be.
    ReleaseAll();

    // Only use hugepages that hold nothing but our objects: anything else allocated in between
    // would land in our holes or keep them from being released.
    std::unordered_map<uintptr_t, std::vector<void*>> by_hugepage;
    std::vector<void*> spare;
    size_t full = 0;
    for (size_t allocated = 0; full < hugepages; ++allocated) {
      CHECK_LT(allocated, 4 * hugepages * kPagesPerHugePage + 10000) << "Could not fill hugepages";
      void* p = malloc(kTCMallocPageSize);
      memset(p, 1, kTCMallocPageSize);
      auto& objects = by_hugepage[reinterpret_cast<uintptr_t>(p) / kHugePageSize];
      objects.push_back(p);
      if (objects.size() == kPagesPerHugePage) {
        ++full;
      }
    }

    for (auto& [_, objects] : by_hugepage) {
      if (objects.size() < kPagesPerHugePage) {
        live_.insert(live_.end(), objects.begin(), objects.end());
        continue;
      }
      for (void* p : objects) {
        auto page = reinterpret_cast<uintptr_t>(p) % kHugePageSize / kTCMallocPageSize;
        if (page / hole_pages % 2 == 0) {
          free(p);
        } else {
          live_.push_back(p);
        }
      }
      holes_ += kPagesPerHugePage / (2 * hole_pages);
      hole_bytes_ += kHugePageSize / 2;
    }
    FlushFrontEndCaches();
  }

  ~HoleyHeap() {
    for (void* p : live_) {
      free(p);
    }
  }

  size_t holes() const { return holes_; }
  size_t hole_bytes() const { return hole_bytes_; }

 private:
  std::vector<void*> live_;
  size_t holes_ = 0;
  size_t hole_bytes_ = 0;
};

struct Sample {
  int64_t start_ns;
  int64_t duration_ns;
};

// Longest of the samples that overlap [begin_ns, end_ns).
double MaxOverlappingMs(const std::vector<Sample>& samples, int64_t begin_ns, int64_t end_ns) {
  int64_t max_ns = 0;
  for (const auto& sample : samples) {
    if (sample.start_ns < end_ns && sample.start_ns + sample.duration_ns > begin_ns) {
      max_ns = std::max(max_ns, sample.duration_ns);
    }
  }
  return NsToMs(max_ns);
}

size_t CountOverlappingAtLeast(
    const std::vector<Sample>& samples, int64_t begin_ns, int64_t end_ns, int64_t min_ns) {
  size_t count = 0;
  for (const auto& sample : samples) {
    if (sample.start_ns < end_ns && sample.start_ns + sample.duration_ns > begin_ns &&
        sample.duration_ns >= min_ns) {
      ++count;
    }
  }
  return count;
}

// Runs op on its own thread every interval until Stop(), and records when each call started and
// how long it took.
class Prober {
 public:
  Prober(std::function<void()> op, std::chrono::microseconds interval)
      : thread_(CHECK_RESULT(Thread::Make(
            "tcmalloc", "prober", [this, op = std::move(op), interval] { Run(op, interval); }))) {}

  ~Prober() { Stop(); }

  const std::vector<Sample>& Stop() {
    stop_.store(true, std::memory_order_release);
    if (thread_) {
      thread_->Join();
      thread_ = nullptr;
    }
    return samples_;
  }

 private:
  void Run(const std::function<void()>& op, std::chrono::microseconds interval) {
    while (!stop_.load(std::memory_order_acquire)) {
      auto start = NowNs();
      op();
      samples_.push_back({start, NowNs() - start});
      std::this_thread::sleep_for(interval);
    }
  }

  std::atomic<bool> stop_{false};
  std::vector<Sample> samples_;
  ThreadPtr thread_;
};

// Reading TCMalloc's statistics takes pageheap_lock, as the root MemTracker's consumption functor
// does, so how long this takes is how long the reader waited for the lock.
void ReadAllocatedBytes() {
  benchmark::DoNotOptimize(GetProperty("generic.current_allocated_bytes"));
}

// An allocation too big for the per-CPU and thread caches goes to the page heap under
// pageheap_lock. 4 MiB is a whole number of hugepages, so it never lands in the holes.
void AllocateFromPageHeap() {
  void* p = malloc(4_MB);
  benchmark::DoNotOptimize(p);
  free(p);
}

constexpr auto kProbeInterval = std::chrono::microseconds(50);

// Args: hugepages, hole_pages, chunk_kib. Half of each hugepage is freed in holes of hole_pages
// pages, then released in one call (chunk_kib = 0) or in chunk_kib calls like the old MemTracker
// GC loop. Time is the release; lock_wait_max_ms is the longest a probe thread waited for
// pageheap_lock while it ran.
void BM_ReleaseHoleyHugepages(benchmark::State& state) {
  const size_t hugepages = state.range(0);
  const size_t hole_pages = state.range(1);
  const size_t chunk_bytes = state.range(2) * 1_KB;

  ScopedZeroSkipSubrelease zero_skip_subrelease;
  double stats_wait_max_ms = 0;
  double alloc_wait_max_ms = 0;
  double released_mib = 0;
  size_t holes = 0;
  int64_t total_release_ns = 0;
  for (auto _ : state) {
    HoleyHeap heap(hugepages, hole_pages);
    holes = heap.holes();
    auto unmapped_before = GetProperty("tcmalloc.pageheap_unmapped_bytes");

    Prober stats_prober(ReadAllocatedBytes, kProbeInterval);
    Prober alloc_prober(AllocateFromPageHeap, kProbeInterval);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    auto release_start = NowNs();
    auto release_ns = Release(heap.hole_bytes(), chunk_bytes);
    auto release_end = release_start + release_ns;
    stats_wait_max_ms = std::max(
        stats_wait_max_ms, MaxOverlappingMs(stats_prober.Stop(), release_start, release_end));
    alloc_wait_max_ms = std::max(
        alloc_wait_max_ms, MaxOverlappingMs(alloc_prober.Stop(), release_start, release_end));

    released_mib += UnmappedMiBSince(unmapped_before);
    total_release_ns += release_ns;
    state.SetIterationTime(release_ns / 1e9);
  }

  const auto iterations = static_cast<double>(state.iterations());
  state.counters["holes"] = holes;
  state.counters["released_mib"] = released_mib / iterations;
  state.counters["us_per_hole"] = total_release_ns / 1e3 / iterations / holes;
  state.counters["stats_wait_max_ms"] = stats_wait_max_ms;
  state.counters["alloc_wait_max_ms"] = alloc_wait_max_ms;
  SetLabel(state);
}

BENCHMARK(BM_ReleaseHoleyHugepages)
    ->ArgNames({"hugepages", "hole_pages", "chunk_kib"})
    ->ArgsProduct({{64, 512}, {1, 4, 16, 64, 128}, {0}})
    ->Args({64, 1, 1024})
    ->Iterations(10)
    ->UseManualTime()
    ->Unit(benchmark::kMillisecond);

// What the threads in BM_RaftLockConvoy that hold the lock do with it.
enum class LockHolderWork {
  // Nothing but bookkeeping, so the release cannot reach them.
  kNoAllocation = 0,
  // Allocate a buffer that comes from the page heap every time.
  kPageHeapAllocation = 1,
  // Copy a small struct of strings, like TrackedPeer previous = *peer. Heap sampling sends a
  // fraction of those allocations to the page heap: every sampled allocation does.
  kSampledSmallAllocations = 2,
};

struct PeerLike {
  std::string uuid = std::string(36, 'u');
  std::vector<std::string> host_ports{std::string(40, 'h'), std::string(40, 'p')};
  std::string cloud_info = std::string(64, 'c');
};

constexpr size_t kLockHolderThreads = 4;
constexpr size_t kLockWaiterThreads = 4;
constexpr auto kLockThreadPause = std::chrono::microseconds(100);
constexpr size_t kSampledSmallAllocationsInterval = 64_KB;

// Args: hugepages, chunk_kib, work. kLockHolderThreads threads repeatedly take a simple_spinlock
// (the type of PeerMessageQueue::queue_lock_) and do the given work under it, while
// kLockWaiterThreads threads take it only to bump a counter. Meanwhile the main thread releases
// half of the given hugepages in one-page holes. waiter_wait_max_ms is the longest a thread that
// never allocates waited for the lock during the release; baseline_wait_max_ms is the same before
// the release started.
void BM_RaftLockConvoy(benchmark::State& state) {
  const size_t hugepages = state.range(0);
  const size_t chunk_bytes = state.range(1) * 1_KB;
  const auto work = static_cast<LockHolderWork>(state.range(2));

  ScopedZeroSkipSubrelease zero_skip_subrelease;
  const auto sampling_interval = MallocExtension::GetProfileSamplingInterval();
  double waiter_wait_max_ms = 0;
  double baseline_wait_max_ms = 0;
  double holder_hold_max_ms = 0;
  size_t waits_over_10ms = 0;
  for (auto _ : state) {
    HoleyHeap heap(hugepages, /* hole_pages= */ 1);
    if (work == LockHolderWork::kSampledSmallAllocations) {
      MallocExtension::SetProfileSamplingInterval(kSampledSmallAllocationsInterval);
    }

    simple_spinlock lock;
    PeerLike peer;
    int64_t counter = 0;
    std::atomic<bool> stop{false};
    std::vector<std::vector<Sample>> waits(kLockHolderThreads + kLockWaiterThreads);
    std::vector<std::vector<Sample>> holds(kLockHolderThreads);
    std::vector<ThreadPtr> threads;
    for (size_t i = 0; i != kLockHolderThreads + kLockWaiterThreads; ++i) {
      threads.push_back(CHECK_RESULT(Thread::Make("tcmalloc", "raft_lock", [&, i] {
        const bool holder = i < kLockHolderThreads;
        while (!stop.load(std::memory_order_acquire)) {
          auto start = NowNs();
          lock.lock();
          auto locked = NowNs();
          if (holder && work == LockHolderWork::kPageHeapAllocation) {
            void* p = malloc(512_KB);
            static_cast<char*>(p)[0] = 1;
            benchmark::DoNotOptimize(p);
            free(p);
          } else if (holder && work == LockHolderWork::kSampledSmallAllocations) {
            PeerLike previous = peer;
            benchmark::DoNotOptimize(previous);
          }
          benchmark::DoNotOptimize(++counter);
          auto unlocked = NowNs();
          lock.unlock();
          waits[i].push_back({start, locked - start});
          if (holder) {
            holds[i].push_back({locked, unlocked - locked});
          }
          std::this_thread::sleep_for(kLockThreadPause);
        }
      })));
    }

    auto baseline_start = NowNs();
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    auto release_start = NowNs();
    auto release_ns = Release(heap.hole_bytes(), chunk_bytes);
    auto release_end = release_start + release_ns;
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    stop.store(true, std::memory_order_release);
    for (auto& thread : threads) {
      thread->Join();
    }
    MallocExtension::SetProfileSamplingInterval(sampling_interval);

    for (size_t i = kLockHolderThreads; i != waits.size(); ++i) {
      waiter_wait_max_ms = std::max(
          waiter_wait_max_ms, MaxOverlappingMs(waits[i], release_start, release_end));
      baseline_wait_max_ms = std::max(
          baseline_wait_max_ms, MaxOverlappingMs(waits[i], baseline_start, release_start));
      waits_over_10ms += CountOverlappingAtLeast(waits[i], release_start, release_end, 10'000'000);
    }
    for (const auto& thread_holds : holds) {
      holder_hold_max_ms = std::max(
          holder_hold_max_ms, MaxOverlappingMs(thread_holds, release_start, release_end));
    }
    state.SetIterationTime(release_ns / 1e9);
  }

  state.counters["waiter_wait_max_ms"] = waiter_wait_max_ms;
  state.counters["baseline_wait_max_ms"] = baseline_wait_max_ms;
  state.counters["holder_hold_max_ms"] = holder_hold_max_ms;
  state.counters["waits_over_10ms"] = waits_over_10ms / static_cast<double>(state.iterations());
  SetLabel(state);
}

BENCHMARK(BM_RaftLockConvoy)
    ->ArgNames({"hugepages", "chunk_kib", "work"})
    ->ArgsProduct({{128}, {0, 1024}, {0, 1, 2}})
    ->Iterations(5)
    ->UseManualTime()
    ->Unit(benchmark::kMillisecond);

// Allocates bytes as objects of object_size bytes and writes one byte to each OS page, so the time
// it takes includes faulting in pages that were released.
std::vector<void*> AllocateAndTouch(size_t bytes, size_t object_size) {
  std::vector<void*> objects;
  objects.reserve(bytes / object_size);
  for (size_t allocated = 0; allocated < bytes; allocated += object_size) {
    auto* p = static_cast<char*>(malloc(object_size));
    for (size_t offset = 0; offset < object_size; offset += kOsPageSize) {
      p[offset] = 1;
    }
    objects.push_back(p);
  }
  return objects;
}

void FreeAll(std::vector<void*>* objects) {
  for (void* p : *objects) {
    free(p);
  }
  objects->clear();
}

// Long-lived objects interleaved with the ones the benchmark frees and allocates again, so that
// the freed memory is holes in hugepages that stay in use, as in a server's heap, rather than
// whole hugepages.
class InterleavedResidents {
 public:
  InterleavedResidents(size_t bytes, size_t object_size) {
    auto objects = AllocateAndTouch(2 * bytes, object_size);
    for (size_t i = 0; i != objects.size(); ++i) {
      if (i % 2 == 0) {
        free(objects[i]);
      } else {
        residents_.push_back(objects[i]);
      }
    }
    FlushFrontEndCaches();
  }

  ~InterleavedResidents() { FreeAll(&residents_); }

 private:
  std::vector<void*> residents_;
};

constexpr size_t kReuseBytes = 256_MB;
constexpr auto kReuseDelay = std::chrono::milliseconds(100);

// Args: object_kib, release. Frees kReuseBytes of objects, releases the freed memory to the OS
// right away if release is 1 (what TCMalloc's background thread does over time, or a
// synchronous GC does at once), and allocates and touches the same amount again kReuseDelay
// later. Time is the reallocation; minor_faults are those taken during it.
void BM_ReuseAfterRelease(benchmark::State& state) {
  const size_t object_size = state.range(0) * 1_KB;
  const bool release = state.range(1);

  ReleaseAll();
  InterleavedResidents residents(kReuseBytes, object_size);
  double minor_faults = 0;
  double released_mib = 0;
  auto objects = AllocateAndTouch(kReuseBytes, object_size);
  for (auto _ : state) {
    FreeAll(&objects);
    FlushFrontEndCaches();
    auto unmapped_before = GetProperty("tcmalloc.pageheap_unmapped_bytes");
    if (release) {
      ScopedZeroSkipSubrelease zero_skip_subrelease;
      MallocExtension::ReleaseMemoryToSystem(kReuseBytes);
    }
    released_mib += UnmappedMiBSince(unmapped_before);
    std::this_thread::sleep_for(kReuseDelay);

    RusageDelta rusage(RUSAGE_THREAD);
    auto start = NowNs();
    objects = AllocateAndTouch(kReuseBytes, object_size);
    state.SetIterationTime((NowNs() - start) / 1e9);
    minor_faults += rusage.MinorFaults();
  }
  FreeAll(&objects);

  const auto iterations = static_cast<double>(state.iterations());
  state.counters["minor_faults"] = minor_faults / iterations;
  state.counters["released_mib"] = released_mib / iterations;
  SetLabel(state);
}

BENCHMARK(BM_ReuseAfterRelease)
    ->ArgNames({"object_kib", "release"})
    ->ArgsProduct({{32, 4096}, {0, 1}})
    ->Iterations(10)
    ->UseManualTime()
    ->Unit(benchmark::kMillisecond);

// Runs TCMalloc's ProcessBackgroundActions loop on its own thread, as yb-tserver does with
// --tcmalloc_background_thread, until destroyed.
class BackgroundThread {
 public:
  explicit BackgroundThread(size_t release_bytes_per_sec)
      : old_rate_(MallocExtension::GetBackgroundReleaseRate()) {
    MallocExtension::SetBackgroundReleaseRate(
        MallocExtension::BytesPerSecond{release_bytes_per_sec});
    MallocExtension::SetBackgroundProcessActionsEnabled(true);
    thread_ = CHECK_RESULT(Thread::Make(
        "tcmalloc", "tcmalloc_bkgrnd", [] { MallocExtension::ProcessBackgroundActions(); }));
  }

  ~BackgroundThread() {
    MallocExtension::SetBackgroundProcessActionsEnabled(false);
    thread_->Join();
    MallocExtension::SetBackgroundReleaseRate(old_rate_);
  }

 private:
  const MallocExtension::BytesPerSecond old_rate_;
  ThreadPtr thread_;
};

constexpr size_t kChurnBytes = 256_MB;
constexpr size_t kChurnObjectSize = 32_KB;
constexpr auto kChurnIdle = std::chrono::milliseconds(100);

// Args: rate_mib_per_sec, zero_skip_subrelease. Each iteration allocates and touches kChurnBytes,
// frees it and idles for kChurnIdle, while TCMalloc's background thread releases free memory at
// the given rate (0 = no background thread). zero_skip_subrelease = 1 lets it release memory that
// was in use within TCMalloc's lookbacks. Time is the allocation; minor_faults are those taken
// during it; cpu_ms is the whole process's CPU time per iteration, including the background
// thread's.
void BM_BackgroundReleaseChurn(benchmark::State& state) {
  const size_t rate = state.range(0) * 1_MB;
  const bool zero_skip_subrelease = state.range(1);

  ReleaseAll();
  std::optional<ScopedZeroSkipSubrelease> zero_skip_subrelease_scope;
  if (zero_skip_subrelease) {
    zero_skip_subrelease_scope.emplace();
  }
  InterleavedResidents residents(kChurnBytes, kChurnObjectSize);
  std::optional<BackgroundThread> background_thread;
  if (rate > 0) {
    background_thread.emplace(rate);
  }

  RusageDelta process_rusage(RUSAGE_SELF);
  double minor_faults = 0;
  double allocate_max_ms = 0;
  for (auto _ : state) {
    RusageDelta rusage(RUSAGE_THREAD);
    auto start = NowNs();
    auto objects = AllocateAndTouch(kChurnBytes, kChurnObjectSize);
    auto allocate_ns = NowNs() - start;
    state.SetIterationTime(allocate_ns / 1e9);
    allocate_max_ms = std::max(allocate_max_ms, NsToMs(allocate_ns));
    minor_faults += rusage.MinorFaults();
    FreeAll(&objects);
    std::this_thread::sleep_for(kChurnIdle);
  }

  const auto iterations = static_cast<double>(state.iterations());
  state.counters["minor_faults"] = minor_faults / iterations;
  state.counters["allocate_max_ms"] = allocate_max_ms;
  state.counters["cpu_ms"] = process_rusage.CpuMs() / iterations;
  SetLabel(state);
}

BENCHMARK(BM_BackgroundReleaseChurn)
    ->ArgNames({"rate_mib_per_sec", "zero_skip_subrelease"})
    ->ArgsProduct({{0, 16, 64}, {0, 1}})
    ->Iterations(50)
    ->UseManualTime()
    ->Unit(benchmark::kMillisecond);

} // namespace
} // namespace yb

#endif // YB_GOOGLE_TCMALLOC
