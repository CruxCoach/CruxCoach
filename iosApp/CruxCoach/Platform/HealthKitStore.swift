import CruxCoachCore
import Foundation
import HealthKit

/// The core's `HealthStore` on HealthKit: the iOS counterpart of Android's Health Connect client.
/// Reads sleep analysis and climbing workouts, writes dietary energy, protein, carbohydrates, fat and
/// water with a sync identifier and version, so a rewrite replaces the earlier sample.
///
/// Every failure is an answer, never a crash: a build without the HealthKit entitlement (the unsigned
/// device IPA) gets errors from HealthKit, which come back as "failed" / `ok = false`, and the settings
/// card then says the link is unavailable. Callbacks run on HealthKit's queues; the core accepts that.
final class HealthKitStore: NSObject, HealthStore {
    private let store = HKHealthStore()

    func isAvailable() -> Bool { HKHealthStore.isHealthDataAvailable() }

    func requestAuthorization(read: [String], write: [String], onDone: @escaping (String) -> Void) {
        guard isAvailable() else { onDone("failed"); return }
        let share = Self.shareTypes(write)
        let readTypes = Self.readTypes(read)
        guard !readTypes.isEmpty || !share.isEmpty else { onDone("failed"); return }
        store.requestAuthorization(toShare: share, read: readTypes) { success, error in
            onDone(success && error == nil ? "done" : "failed")
        }
    }

    func requestStatus(read: [String], write: [String], onStatus: @escaping (String) -> Void) {
        guard isAvailable() else { onStatus("failed"); return }
        let share = Self.shareTypes(write)
        let readTypes = Self.readTypes(read)
        guard !readTypes.isEmpty || !share.isEmpty else { onStatus("failed"); return }
        store.getRequestStatusForAuthorization(toShare: share, read: readTypes) { status, error in
            guard error == nil else { onStatus("failed"); return }
            switch status {
            case .unnecessary: onStatus("asked")
            case .shouldRequest: onStatus("notAsked")
            default: onStatus("failed")
            }
        }
    }

    func writeStatus(write: [String]) -> String {
        guard isAvailable() else { return "notDetermined" }
        let types = write.compactMap { Self.sampleType($0) }
        guard !types.isEmpty, types.count == write.count else { return "notDetermined" }
        let statuses = types.map { store.authorizationStatus(for: $0) }
        if statuses.contains(.sharingDenied) { return "denied" }
        if statuses.allSatisfy({ $0 == .sharingAuthorized }) { return "authorized" }
        return "notDetermined"
    }

    func sleepSamples(fromMs: Int64, toMs: Int64, onResult: @escaping (HealthSleepResult) -> Void) {
        guard isAvailable(), let type = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) else {
            onResult(HealthSleepResult(ok: false, samples: []))
            return
        }
        let predicate = HKQuery.predicateForSamples(withStart: Self.date(fromMs), end: Self.date(toMs), options: [])
        let sort = [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]
        let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: sort) { _, samples, error in
            guard error == nil else {
                onResult(HealthSleepResult(ok: false, samples: []))
                return
            }
            let mapped: [HealthSleepSample] = (samples ?? []).compactMap { sample in
                guard let category = sample as? HKCategorySample else { return nil }
                return HealthSleepSample(startMs: Self.millis(category.startDate),
                                         endMs: Self.millis(category.endDate),
                                         stage: Self.stage(category.value))
            }
            onResult(HealthSleepResult(ok: true, samples: mapped))
        }
        store.execute(query)
    }

    func climbingWorkouts(fromMs: Int64, toMs: Int64, onResult: @escaping (HealthWorkoutResult) -> Void) {
        guard isAvailable() else {
            onResult(HealthWorkoutResult(ok: false, workouts: []))
            return
        }
        let predicate = HKQuery.predicateForSamples(withStart: Self.date(fromMs), end: Self.date(toMs), options: .strictStartDate)
        let sort = [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]
        let query = HKSampleQuery(sampleType: HKObjectType.workoutType(), predicate: predicate,
                                  limit: HKObjectQueryNoLimit, sortDescriptors: sort) { _, samples, error in
            guard error == nil else {
                onResult(HealthWorkoutResult(ok: false, workouts: []))
                return
            }
            // Climbing only; HealthKit workouts carry no title, so the core maps them by type.
            let mapped: [HealthWorkoutSample] = (samples ?? []).compactMap { sample in
                guard let workout = sample as? HKWorkout, workout.workoutActivityType == .climbing else { return nil }
                return HealthWorkoutSample(id: workout.uuid.uuidString,
                                           startMs: Self.millis(workout.startDate),
                                           endMs: Self.millis(workout.endDate),
                                           title: "")
            }
            onResult(HealthWorkoutResult(ok: true, workouts: mapped))
        }
        store.execute(query)
    }

    func writeNutrition(samples: [HealthNutritionSample], deleteSyncIds: [String], onDone: @escaping (String) -> Void) {
        guard isAvailable() else { onDone("failed"); return }
        let now = Date()
        var objects: [HKObject] = []
        for item in samples {
            guard let type = Self.sampleType(item.typeCode) as? HKQuantityType,
                  item.value.isFinite, item.value >= 0 else { continue }
            // Never in the future: HealthKit gets the entry at most "now".
            let end = min(Self.date(item.endMs), now)
            let start = min(Self.date(item.startMs), end)
            var metadata: [String: Any] = [
                HKMetadataKeySyncIdentifier: item.syncId,
                HKMetadataKeySyncVersion: NSNumber(value: item.version),
                HKMetadataKeyWasUserEntered: NSNumber(value: true),
            ]
            if !item.foodName.isEmpty { metadata[HKMetadataKeyFoodType] = item.foodName }
            let quantity = HKQuantity(unit: Self.unit(item.typeCode), doubleValue: item.value)
            objects.append(HKQuantitySample(type: type, quantity: quantity, start: start, end: end, metadata: metadata))
        }
        let deleteIds = deleteSyncIds
        let finishWithDeletes: () -> Void = { [weak self] in
            guard let self else { onDone("failed"); return }
            self.deleteOwnSamples(syncIds: deleteIds, onDone: onDone)
        }
        if objects.isEmpty {
            finishWithDeletes()
            return
        }
        store.save(objects) { success, error in
            guard success, error == nil else { onDone("failed"); return }
            finishWithDeletes()
        }
    }

    /// Deletes only this app's own samples (HealthKit refuses others anyway) with one of the sync ids.
    private func deleteOwnSamples(syncIds: [String], onDone: @escaping (String) -> Void) {
        guard !syncIds.isEmpty else { onDone("done"); return }
        let types = Self.writeCodes.compactMap { Self.sampleType($0) as? HKQuantityType }
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForObjects(from: HKSource.default()),
            HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeySyncIdentifier, allowedValues: syncIds),
        ])
        let group = DispatchGroup()
        let lock = NSLock()
        var failed = false
        for type in types {
            group.enter()
            store.deleteObjects(of: type, predicate: predicate) { success, _, error in
                // Nothing of this type to delete is not a failure.
                let nothingThere = (error as? HKError)?.code == .errorNoData
                if !nothingThere && (!success || error != nil) {
                    lock.lock(); failed = true; lock.unlock()
                }
                group.leave()
            }
        }
        group.notify(queue: .global(qos: .utility)) {
            lock.lock(); let result = failed ? "failed" : "done"; lock.unlock()
            onDone(result)
        }
    }

    // MARK: - Type codes (the core's HealthTypes)

    private static let writeCodes = ["dietaryEnergyConsumed", "dietaryProtein", "dietaryCarbohydrates", "dietaryFatTotal", "dietaryWater"]

    private static func sampleType(_ code: String) -> HKSampleType? {
        switch code {
        case "sleepAnalysis": return HKObjectType.categoryType(forIdentifier: .sleepAnalysis)
        case "workouts": return HKObjectType.workoutType()
        case "dietaryEnergyConsumed": return HKObjectType.quantityType(forIdentifier: .dietaryEnergyConsumed)
        case "dietaryProtein": return HKObjectType.quantityType(forIdentifier: .dietaryProtein)
        case "dietaryCarbohydrates": return HKObjectType.quantityType(forIdentifier: .dietaryCarbohydrates)
        case "dietaryFatTotal": return HKObjectType.quantityType(forIdentifier: .dietaryFatTotal)
        case "dietaryWater": return HKObjectType.quantityType(forIdentifier: .dietaryWater)
        default: return nil
        }
    }

    private static func shareTypes(_ codes: [String]) -> Set<HKSampleType> {
        var types = Set<HKSampleType>()
        for code in codes {
            if let type = sampleType(code) { types.insert(type) }
        }
        return types
    }

    /// Only what the coach reads: written types are not asked for reading (Android asks write access only).
    private static func readTypes(_ codes: [String]) -> Set<HKObjectType> {
        var types = Set<HKObjectType>()
        for code in codes {
            if let type = sampleType(code) { types.insert(type) }
        }
        return types
    }

    private static func unit(_ code: String) -> HKUnit {
        switch code {
        case "dietaryEnergyConsumed": return .kilocalorie()
        case "dietaryWater": return .literUnit(with: .milli)
        default: return .gram()
        }
    }

    private static func stage(_ value: Int) -> String {
        switch HKCategoryValueSleepAnalysis(rawValue: value) {
        case .inBed?: return "inBed"
        case .awake?: return "awake"
        case .asleepUnspecified?, .asleepCore?, .asleepDeep?, .asleepREM?: return "asleep"
        default: return "unknown"
        }
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }

    private static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}
