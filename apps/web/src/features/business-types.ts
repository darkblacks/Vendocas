/**
 * Types of establishment offered in the onboarding, grouped. Each type comes pre-set with its usual sales model —
 * B2C (customers come, buy and leave → automatic board from the PDV) or B2B (sells to companies → negotiation funnel).
 * The store can always switch the model. Labels live in messages/*.json under journey.types.
 */
export type SalesModel = "B2C" | "B2B";
export interface BusinessType { key: string; model: SalesModel }
export interface BusinessGroup { group: string; types: BusinessType[] }

export const BUSINESS_GROUPS: BusinessGroup[] = [
  { group: "food", types: [{ key: "pizzeria", model: "B2C" }, { key: "restaurant", model: "B2C" }, { key: "burger", model: "B2C" }, { key: "japanese", model: "B2C" }, { key: "bakery", model: "B2C" }, { key: "coffee_shop", model: "B2C" }, { key: "ice_cream", model: "B2C" }, { key: "meal_delivery", model: "B2C" }, { key: "bar", model: "B2C" }, { key: "grocery", model: "B2C" }, { key: "butcher", model: "B2C" }, { key: "produce", model: "B2C" }, { key: "wine_shop", model: "B2C" }, { key: "beverage_distributor", model: "B2B" }, { key: "food_supplier", model: "B2B" }] },
  { group: "beauty", types: [{ key: "hair_salon", model: "B2C" }, { key: "barbershop", model: "B2C" }, { key: "aesthetics_clinic", model: "B2C" }, { key: "nails", model: "B2C" }, { key: "eyebrows_lashes", model: "B2C" }, { key: "spa", model: "B2C" }, { key: "tattoo", model: "B2C" }, { key: "cosmetics", model: "B2C" }] },
  { group: "health", types: [{ key: "pharmacy", model: "B2C" }, { key: "medical_clinic", model: "B2C" }, { key: "dentist", model: "B2C" }, { key: "physio", model: "B2C" }, { key: "psychology", model: "B2C" }, { key: "nutrition", model: "B2C" }, { key: "optician", model: "B2C" }, { key: "lab", model: "B2C" }] },
  { group: "fitness", types: [{ key: "gym", model: "B2C" }, { key: "pilates_yoga", model: "B2C" }, { key: "martial_arts", model: "B2C" }, { key: "personal_trainer", model: "B2C" }] },
  { group: "fashion", types: [{ key: "clothing", model: "B2C" }, { key: "shoe_store", model: "B2C" }, { key: "accessories", model: "B2C" }, { key: "jewelry", model: "B2C" }, { key: "lingerie", model: "B2C" }, { key: "kids_fashion", model: "B2C" }, { key: "thrift", model: "B2C" }, { key: "clothing_wholesale", model: "B2B" }] },
  { group: "home", types: [{ key: "furniture", model: "B2C" }, { key: "decor", model: "B2C" }, { key: "appliances", model: "B2C" }, { key: "building_materials", model: "B2C" }, { key: "florist", model: "B2C" }, { key: "garden", model: "B2C" }] },
  { group: "pets", types: [{ key: "pet_shop", model: "B2C" }, { key: "vet", model: "B2C" }, { key: "pet_grooming", model: "B2C" }] },
  { group: "auto", types: [{ key: "mechanic", model: "B2C" }, { key: "car_wash", model: "B2C" }, { key: "auto_parts", model: "B2C" }, { key: "car_dealer", model: "B2C" }, { key: "driving_school", model: "B2C" }] },
  { group: "retail", types: [{ key: "electronics", model: "B2C" }, { key: "phone_repair", model: "B2C" }, { key: "bookstore", model: "B2C" }, { key: "toys", model: "B2C" }, { key: "sports", model: "B2C" }, { key: "music", model: "B2C" }, { key: "tobacco", model: "B2C" }, { key: "online_store", model: "B2C" }] },
  { group: "services", types: [{ key: "school", model: "B2C" }, { key: "language_school", model: "B2C" }, { key: "laundry", model: "B2C" }, { key: "photography", model: "B2C" }, { key: "events", model: "B2C" }, { key: "travel", model: "B2C" }, { key: "hotel", model: "B2C" }, { key: "real_estate", model: "B2C" }, { key: "home_services", model: "B2C" }, { key: "coworking", model: "B2B" }] },
  { group: "b2b", types: [{ key: "wholesale", model: "B2B" }, { key: "manufacturer", model: "B2B" }, { key: "sales_rep", model: "B2B" }, { key: "printing", model: "B2B" }, { key: "accounting", model: "B2B" }, { key: "law_firm", model: "B2B" }, { key: "consulting", model: "B2B" }, { key: "marketing_agency", model: "B2B" }, { key: "software", model: "B2B" }, { key: "it_services", model: "B2B" }, { key: "facilities", model: "B2B" }, { key: "logistics", model: "B2B" }, { key: "agro", model: "B2B" }, { key: "construction", model: "B2B" }, { key: "uniforms", model: "B2B" }] },
  { group: "other", types: [{ key: "other_b2c", model: "B2C" }, { key: "other_b2b", model: "B2B" }] },
];

export const BUSINESS_TYPES: BusinessType[] = BUSINESS_GROUPS.flatMap((g) => g.types);
export const defaultModel = (key: string): SalesModel => BUSINESS_TYPES.find((t) => t.key === key)?.model ?? "B2C";
