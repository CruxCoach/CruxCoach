import CruxCoachCore
import XCTest
@testable import CruxCoach

/// Android's `MealTextSplitTest`, run on Kotlin/Native against the BLS table the app bundles: the typed-meal parser
/// uses many regular expressions, and Kotlin/Native's regex engine differs from the JVM's in corners (the negated
/// `\p{…}` class bug of 2026-10-17). The JVM test alone would not see such a difference on iPhone.
final class MealTextSplitTests: XCTestCase {

    private static var cached: FoodMatcher?

    private func matcher() throws -> FoodMatcher {
        if let m = Self.cached { return m }
        let resources = try XCTUnwrap(Bundle.main.resourceURL)
        let url = resources.appendingPathComponent("fuel/bls_4_0_macros.tsv")
        let text = try String(contentsOf: url, encoding: .utf8)
        let foods: [BlsFood] = BlsTable.shared.parse(text: text)
        XCTAssertGreaterThan(foods.count, 7000, "the bundled BLS table is complete")
        let m = FoodMatcher(foods: foods)
        Self.cached = m
        return m
    }

    private struct Read {
        let name: String
        let grams: Int
        let bls: String?
    }

    /// name, grams and best BLS name per food, in order (Android `read`).
    private func read(_ text: String) throws -> [Read] {
        let m = try matcher()
        let score: (String) -> KotlinDouble = { name in
            let best = m.match(food: DetectedFood(nameDe: name, nameEn: name, grams: 100.0), limit: 1)
            return KotlinDouble(value: best.first?.score ?? 0.0)
        }
        let result = MealTextParser.shared.parse(text: text, foodScore: score)
        return result.foods.map { f in
            let best = m.match(food: f, limit: 1)
            return Read(name: f.nameDe, grams: Int(f.grams), bls: best.first?.food.nameDe)
        }
    }

    private func names(_ text: String) throws -> [String] { try read(text).map { $0.name } }
    private func grams(_ text: String) throws -> [Int] { try read(text).map { $0.grams } }

    func testAmountsStartTheNextFoodWhenNothingSeparatesThem() throws {
        XCTAssertEqual(try names("2 eier 1 banane 200ml milch"), ["Eier", "Banane", "Milch"])
        XCTAssertEqual(try grams("2 eier 1 banane 200ml milch"), [120, 120, 200])
        XCTAssertEqual(try names("300g Magerquark 1 Banane 30g Haferflocken"), ["Magerquark", "Banane", "Haferflocken"])
        XCTAssertEqual(try grams("300g Magerquark 1 Banane 30g Haferflocken"), [300, 120, 30])
        XCTAssertEqual(try grams("Reis 200g, Hähnchen 150g"), [200, 150])
        // The decimal comma survives (the word split keeps "1,5").
        XCTAssertEqual(try grams("Wasser 1,5 l"), [1500])
    }

    func testAListWithoutSeparatorsIsSplitAndADishNameIsNot() throws {
        XCTAssertEqual(try names("Banane Apfel Birne"), ["Banane", "Apfel", "Birne"])
        XCTAssertEqual(try names("hähnchen reis gemüse"), ["Hähnchen", "Reis", "Gemüse"])
        XCTAssertEqual(try names("chicken rice broccoli"), ["Chicken", "Rice", "Broccoli"])
        XCTAssertEqual(try names("Pizza Margherita"), ["Pizza margherita"])
        XCTAssertEqual(try names("greek yogurt with honey and walnuts"), ["Greek yogurt", "Honey", "Walnuts"])
    }

    func testFractionsHalvesAndMultipliers() throws {
        XCTAssertEqual(try grams("1/2 Banane"), [60])
        XCTAssertEqual(try grams("eine halbe Pizza"), [225])
        XCTAssertEqual(try grams("2 x Ei"), [120])
        XCTAssertEqual(try names("2x Spiegelei und Toast"), ["Spiegelei", "Toast"])
        XCTAssertEqual(try grams("1/2 cup rice and 6 oz salmon"), [120, 170])
    }

    func testCoffeeAdditionsToppingsAndToppedBreads() throws {
        XCTAssertEqual(try grams("Kaffee mit Milch und Zucker"), [150, 30, 6])
        XCTAssertEqual(try grams("Skyr mit Heidelbeeren und Honig"), [150, 125, 20])
        XCTAssertEqual(try names("Käsebrot und ein Glas Milch"), ["Brot", "Käse", "Milch"])
        XCTAssertEqual(try grams("Käsebrot und ein Glas Milch").last, 200)
        XCTAssertEqual(try names("Rührei aus 3 Eiern"), ["Rührei"])
        XCTAssertEqual(try grams("Rührei aus 3 Eiern"), [180])
        XCTAssertEqual(try names("Pommes rot weiß"), ["Pommes", "Ketchup", "Mayonnaise"])
    }

    func testEverydayWordsFindThePlainFoodAndSupplementsNothingWrong() throws {
        XCTAssertEqual(try read("2 Bananen, 1 Apfel").first?.bls, "Banane roh")
        let bread = try read("Vollkornbrot mit Gurke")
        XCTAssertEqual(bread.count, 2)
        XCTAssertEqual(bread.last?.bls, "Gurke roh")
        let whey = try read("1 Scoop Whey in 300 ml Milch")
        XCTAssertEqual(whey.map { $0.name }, ["Whey", "Milch"])
        XCTAssertNil(whey.first?.bls)
        // Powder without an amount is a scoop, not the 150 g default.
        XCTAssertEqual(try grams("Whey"), [30])
        let bar = try read("a protein bar")
        XCTAssertFalse(bar.isEmpty)
        XCTAssertNil(bar.first?.bls)
    }
}
