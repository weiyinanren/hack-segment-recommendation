#!/usr/bin/env python3
"""Build synthetic CPG/beauty audiences from a real tenant taxonomy.

Reads the flattened segment CSV produced by parse_segment_mapping.py and emits
rows in the same schema as data/audiences.csv, so train.py can consume it
directly.

Audiences are drawn from hand-written beauty-marketing themes rather than
sampled uniformly. That matters: co-occurrence within an audience is the only
signal behind clients/*/similarity.json, so random baskets would train a
recommender on noise. Demographic attributes deliberately appear across several
themes to create realistic bridges between them.

Both the segment combination and the generated name are kept unique across the
run by rejection sampling, so no audience is a duplicate of another.
"""

from __future__ import annotations

import argparse
import csv
import random
import re
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent / "data"

# (attribute, values) pairs. `core` anchors the theme and is always present;
# `optional` supplies the variation. values=None means "any value in the
# taxonomy", which is only safe for attributes where every value fits the theme.
THEMES: list[dict] = [
    {
        "name": "Anti-Aging Prestige",
        "core": [
            ("Skin_Concern_Primary", ["Aging"]),
            ("Anti_Aging_Product_Interest", ["Y"]),
        ],
        "optional": [
            ("Retinol_Product_User", ["Y"]),
            ("Household_Income_Range", ["150K-200K", "200K-300K", "300K+"]),
            ("Prestige_Beauty_Spend_Tier", ["Premium", "High"]),
            ("Lancome_Purchase_History", ["Y"]),
            ("Helena_Rubinstein_Purchase_History", ["Y"]),
            ("Serum_Spend_Tier", ["Premium", "High"]),
            ("Generation_Cohort", ["GenX", "BabyBoomer"]),
            ("Collagen_Supplement_User", ["Y"]),
            ("Niacinamide_Product_User", ["Y"]),
            ("Menopause_Skincare_Interest", ["Y"]),
            ("Moisturizer_Spend_Tier", ["Premium", "High"]),
            ("Serum_Purchase_Frequency", ["Monthly", "Weekly"]),
            ("Microcurrent_Device_Owner", ["Y"]),
            ("LED_Skincare_Device_Owner", ["Y"]),
            ("Department_Store_Beauty_Counter_Shopper", ["Y"]),
        ],
    },
    {
        "name": "Gen Z Social Beauty Explorer",
        "core": [
            ("Generation_Cohort", ["GenZ"]),
            ("Primary_Social_Platform", ["TikTok", "Instagram"]),
        ],
        "optional": [
            ("Influencer_Content_Engagement_Level", ["High"]),
            ("Beauty_Tutorial_Video_Viewer", ["Y"]),
            ("Makeup_Usage_Frequency", ["Daily", "FewTimesWeek"]),
            ("Maybelline_Purchase_History", ["Y"]),
            ("Price_Sensitivity_Level", ["High"]),
            ("Beauty_Category_Discovery_Channel", ["SocialMedia", "Influencer"]),
            ("Virtual_Try_On_Tool_Usage", ["Y"]),
            ("Household_Income_Range", ["Under30K", "30K-50K"]),
            ("NYX_Professional_Purchase_History", ["Y"]),
            ("Urban_Decay_Purchase_History", ["Y"]),
            ("Livestream_Shopping_Beauty_Buyer", ["Y"]),
            ("Beauty_Product_Unboxing_Content_Viewer", ["Y"]),
            ("Limited_Edition_Collection_Buyer", ["Y"]),
            ("New_Product_Early_Adopter_Score", ["7", "8", "9", "10"]),
            ("Employment_Status", ["Student"]),
            ("Mass_Market_Retailer_Shopper", ["Y"]),
        ],
    },
    {
        "name": "Sensitive Skin Gentle Care",
        "core": [
            ("Skin_Type", ["Sensitive"]),
            ("Skin_Concern_Primary", ["Sensitivity", "Redness"]),
        ],
        "optional": [
            ("La_Roche_Posay_Purchase_History", ["Y"]),
            ("Dermatologist_Recommended_Product_Preference", ["Y"]),
            ("Clean_Beauty_Preference", ["Y"]),
            ("Vichy_Purchase_History", ["Y"]),
            ("Clinical_Skincare_Interest", ["Y"]),
            ("Skincare_Purchase_Frequency", ["Monthly", "Weekly"]),
            ("Gender", ["F"]),
            ("Hypoallergenic_Product_Preference", ["Y"]),
            ("Fragrance_Free_Product_Preference", ["Y"]),
            ("Beauty_Product_Ingredient_Allergy_Flag", ["Y"]),
            ("Dermocosmetics_Buyer", ["Y"]),
            ("CeraVe_Purchase_History", ["Y"]),
            ("Mixa_Purchase_History", ["Y"]),
            ("Cleansing_Spend_Tier", ["Medium", "High"]),
            ("Beauty_Ingredient_Research_Behavior", ["Always", "Sometimes"]),
        ],
    },
    {
        "name": "Acne-Prone Young Adults",
        "core": [
            ("Skin_Concern_Primary", ["Acne"]),
            ("Skin_Type", ["Oily", "Combination"]),
        ],
        "optional": [
            ("Teen_Skincare_Product_Buyer", ["Y"]),
            ("Generation_Cohort", ["GenZ", "Millennial"]),
            ("Cleansing_Purchase_Frequency", ["Monthly", "Weekly"]),
            ("Exfoliation_Purchase_Frequency", ["Monthly", "Quarterly"]),
            ("Life_Stage", ["YoungSingle"]),
            ("CeraVe_Purchase_History", ["Y"]),
            ("Niacinamide_Product_User", ["Y"]),
            ("Skin_Diagnostic_Quiz_Completion", ["Y"]),
            ("AI_Skin_Diagnostic_Tool_Usage", ["Y"]),
            ("Mask_Treatment_Purchase_Frequency", ["Weekly", "Monthly"]),
            ("Beauty_Review_Site_Visitor", ["Y"]),
            ("Mass_Beauty_Spend_Tier", ["Low", "Medium"]),
            ("Education_Level", ["SomeCollege", "HighSchool"]),
        ],
    },
    {
        "name": "Affluent Skincare Enthusiast",
        "core": [
            ("Household_Income_Range", ["200K-300K", "300K+"]),
            ("Skincare_Spend_Tier", ["Premium", "High"]),
        ],
        "optional": [
            ("Skincare_Routine_Step_Count", ["7", "8", "9", "10"]),
            ("Vitamin_C_Serum_User", ["Y"]),
            ("Hyaluronic_Acid_Product_User", ["Y"]),
            ("SkinCeuticals_Purchase_History", ["Y"]),
            ("Kiehls_Purchase_History", ["Y"]),
            ("Occupation_Category", ["WhiteCollar", "Technology", "Healthcare"]),
            ("Education_Level", ["Master", "Doctorate"]),
            ("Biotherm_Purchase_History", ["Y"]),
            ("Serum_Spend_Tier", ["Premium", "High"]),
            ("Beauty_Fridge_Owner", ["Y"]),
            ("Personalized_Beauty_Product_Interest", ["Y"]),
            ("Investment_Account_Holder", ["Y"]),
            ("Homeownership_Status", ["Own"]),
            ("Credit_Score_Band", ["Excellent", "VeryGood"]),
        ],
    },
    {
        "name": "Clean and Sustainable Beauty Advocate",
        "core": [
            ("Clean_Beauty_Preference", ["Y"]),
            ("Cruelty_Free_Preference", ["Y"]),
        ],
        "optional": [
            ("Vegan_Beauty_Product_Preference", ["Y"]),
            ("Sustainable_Packaging_Preference", ["Y"]),
            ("Refillable_Packaging_Interest", ["Y"]),
            ("Garnier_Purchase_History", ["Y"]),
            ("Generation_Cohort", ["Millennial", "GenZ"]),
            ("Urban_Rural_Classification", ["Urban"]),
            ("Environmental_Consciousness_Score", ["8", "9", "10"]),
            ("Refill_Program_Participation", ["Y"]),
            ("Dietary_Preference", ["Vegan", "Vegetarian"]),
            ("Gluten_Free_Beauty_Preference", ["Y"]),
            ("Beauty_Ingredient_Research_Behavior", ["Always"]),
            ("Multi_Use_Product_Preference", ["Y"]),
            ("Commute_Method", ["Bike", "Walk", "PublicTransit"]),
        ],
    },
    {
        "name": "At-Home Hair Color DIY",
        "core": [
            ("At_Home_Hair_Color_User", ["Y"]),
            ("Hair_Color_Category_Purchase_Frequency", ["Monthly", "Quarterly"]),
        ],
        "optional": [
            ("Hair_Concern_Primary", ["ColorFading"]),
            ("Garnier_Purchase_History", ["Y"]),
            ("Elvive_Purchase_History", ["Y"]),
            ("Hair_Color_Category_Spend_Tier", ["Low", "Medium"]),
            ("Price_Sensitivity_Level", ["High", "Medium"]),
            ("Household_Income_Range", ["30K-50K", "50K-75K"]),
            ("Coupon_Usage_Frequency", ["Often", "Always"]),
            ("Mass_Market_Retailer_Shopper", ["Y"]),
            ("Beauty_Tutorial_Video_Viewer", ["Y"]),
            ("Hair_Styling_Tool_Owner", ["Y"]),
            ("Curling_Iron_Straightener_Owner", ["Y"]),
            ("Haircare_Purchase_Frequency", ["Monthly", "Quarterly"]),
        ],
    },
    {
        "name": "Salon Professional Haircare",
        "core": [
            ("LOreal_Professionnel_Purchase_History", ["Y"]),
            ("Haircare_Spend_Tier", ["Premium", "High"]),
        ],
        "optional": [
            ("Redken_Purchase_History", ["Y"]),
            ("Matrix_Purchase_History", ["Y"]),
            ("Pureology_Purchase_History", ["Y"]),
            ("Keratin_Treatment_User", ["Y"]),
            ("Hair_Concern_Primary", ["Damage"]),
            ("Haircare_Purchase_Frequency", ["Monthly"]),
            ("Kerastase_Purchase_History", ["Y"]),
            ("Hair_Styling_Category_Spend_Tier", ["High", "Premium"]),
            ("Hair_Extension_User", ["Y"]),
            ("Waxing_Hair_Removal_Service_User", ["Y"]),
            ("Professional_Nail_Salon_User", ["Y"]),
            ("Household_Income_Range", ["100K-150K", "150K-200K"]),
        ],
    },
    {
        "name": "Curly and Coily Hair Care",
        "core": [
            ("Hair_Type", ["Curly", "Coily"]),
            ("Curly_Hair_Method_Follower", ["Y"]),
        ],
        "optional": [
            ("Hair_Concern_Primary", ["Frizz", "Dryness"]),
            ("Hair_Styling_Category_Spend_Tier", ["Medium", "High"]),
            ("Color_Cosmetics_Shade_Range_Needed", ["Deep", "Extended"]),
            ("Hair_Styling_Tool_Owner", ["Y"]),
            ("Ethnicity_Group", None),
            ("Skin_Tone_Range", ["Deep", "Tan"]),
            ("Wig_Topper_User", ["Y"]),
            ("Hair_Extension_User", ["Y"]),
            ("Scalp_Care_Routine_User", ["Y"]),
            ("Haircare_Purchase_Frequency", ["Monthly", "Weekly"]),
            ("Beauty_Tutorial_Video_Viewer", ["Y"]),
        ],
    },
    {
        "name": "Scalp Health Care",
        "core": [
            ("Scalp_Care_Routine_User", ["Y"]),
            ("Hair_Concern_Primary", ["Dandruff", "HairLoss"]),
        ],
        "optional": [
            ("Sensitive_Scalp_Product_User", ["Y"]),
            ("Kerastase_Purchase_History", ["Y"]),
            ("Vichy_Purchase_History", ["Y"]),
            ("Generation_Cohort", ["GenX", "BabyBoomer"]),
            ("Gender", ["M", "F"]),
            ("Dermatologist_Recommended_Product_Preference", ["Y"]),
            ("Wellness_Beauty_Supplement_User", ["Y"]),
            ("Haircare_Spend_Tier", ["Medium", "High"]),
            ("Hypoallergenic_Product_Preference", ["Y"]),
            ("Wig_Topper_User", ["Y"]),
            ("Age", ["41", "45", "49", "51", "56", "58"]),
        ],
    },
    {
        "name": "Fragrance Gifting",
        "core": [
            ("Fragrance_Gift_Set_Buyer", ["Y"]),
            ("Fragrance_Purchase_Frequency", ["Monthly", "Quarterly"]),
        ],
        "optional": [
            ("Holiday_Beauty_Gift_Set_Buyer", ["Y"]),
            ("Preferred_Fragrance_Family", None),
            ("YSL_Beauty_Purchase_History", ["Y"]),
            ("Viktor_Rolf_Purchase_History", ["Y"]),
            ("Fragrance_Spend_Tier", ["Premium", "High"]),
            ("Holiday_Shopper", ["Y"]),
            ("Mugler_Purchase_History", ["Y"]),
            ("Prada_Beauty_Purchase_History", ["Y"]),
            ("Ralph_Lauren_Fragrance_Purchase_History", ["Y"]),
            ("Perfume_Sample_Vial_Buyer", ["Y"]),
            ("Fragrance_Layering_Practice", ["Y"]),
            ("Beauty_Advent_Calendar_Buyer", ["Y"]),
            ("Home_Fragrance_Purchase_Frequency", ["Monthly", "Quarterly"]),
            ("Beauty_Gift_Purchase_Occasion_Frequency", ["3", "4", "5", "6"]),
        ],
    },
    {
        "name": "Sun Protection Conscious",
        "core": [
            ("Sunscreen_Usage_Frequency", ["Daily"]),
            ("SPF_Preference_Level", ["SPF50", "SPF50+"]),
        ],
        "optional": [
            ("Ambre_Solaire_Purchase_History", ["Y"]),
            ("Suncare_Purchase_Frequency", ["Monthly", "Quarterly"]),
            ("Skin_Concern_Primary", ["Hyperpigmentation"]),
            ("Seasonal_Shopper_Type", ["SummerShopper"]),
            ("Region", ["West", "South"]),
            ("Suncare_Spend_Tier", ["Medium", "High"]),
            ("Self_Tanner_Frequency", None),
            ("Tanning_Product_User", ["Y"]),
            ("Skin_Tone_Range", ["Fair", "Light"]),
            ("Dermatologist_Recommended_Product_Preference", ["Y"]),
            ("Gym_Membership_Holder", ["Y"]),
        ],
    },
    {
        "name": "Mens Grooming",
        "core": [
            ("Gender", ["M"]),
            ("Men_Grooming_Product_Buyer", ["Y"]),
        ],
        "optional": [
            ("Men_Grooming_Category_Spend_Tier", ["Medium", "High", "Premium"]),
            ("Occupation_Category", ["WhiteCollar", "Technology"]),
            ("Generation_Cohort", ["Millennial", "GenX"]),
            ("Preferred_Shopping_Channel", ["Online", "Mobile"]),
            ("Skin_Concern_Primary", ["Aging", "None"]),
            ("Men_Grooming_Category_Purchase_Frequency", None),
            ("Oral_Care_Beauty_Adjacent_Buyer", ["Y"]),
            ("Gym_Membership_Holder", ["Y"]),
            ("Hair_Concern_Primary", ["HairLoss"]),
            ("Multi_Use_Product_Preference", ["Y"]),
            ("Smoker_Status", ["Former", "Never"]),
            ("Streaming_Service_Subscriber", ["Y"]),
        ],
    },
    {
        "name": "Deal Seeker",
        "core": [
            ("Coupon_Usage_Frequency", ["Always", "Often"]),
            ("Price_Sensitivity_Level", ["High"]),
        ],
        "optional": [
            ("Price_Promotion_Responsiveness_Score", ["8", "9", "10"]),
            ("Flash_Sale_Purchase_History", ["Y"]),
            ("Household_Income_Range", ["Under30K", "30K-50K"]),
            ("Mass_Beauty_Spend_Tier", ["Low"]),
            ("Gift_With_Purchase_Responsiveness", ["Y"]),
            ("Seasonal_Shopper_Type", ["YearRound"]),
            ("Mass_Market_Retailer_Shopper", ["Y"]),
            ("Sample_Request_Frequency", None),
            ("Loyalty_Points_Redeemer", ["Y"]),
            ("QVC_HSN_Beauty_Shopper", ["Y"]),
            ("Credit_Score_Band", ["Fair", "Poor"]),
            ("Employment_Status", ["PartTime", "Unemployed", "HomeMaker"]),
        ],
    },
    {
        "name": "High-Value Loyalty Member",
        "core": [
            ("Beauty_Loyalty_Tier_Status", ["Platinum", "Gold"]),
            ("Loyalty_Program_Member", ["Y"]),
        ],
        "optional": [
            ("Brand_Loyalty_Score", ["8", "9", "10"]),
            ("Full_Price_Purchase_Ratio", ["0.80", "0.85", "0.90", "0.95"]),
            ("Prestige_Beauty_Spend_Tier", ["Premium", "High"]),
            ("Household_Income_Range", ["100K-150K", "150K-200K"]),
            ("Birthday_Beauty_Gift_Redemption", ["Y"]),
            ("In_Store_Event_Attendance", ["Y"]),
            ("Beauty_Points_Balance_Tier", ["High"]),
            ("Beauty_Retailer_Credit_Card_Holder", ["Y"]),
            ("Net_Promoter_Score", ["9", "10"]),
            ("Beauty_Product_Purchase_Frequency", ["Weekly", "BiWeekly", "Monthly"]),
            ("Cross_Category_Beauty_Buyer", ["Y"]),
            ("In_Store_Beauty_Consultation_User", ["Y"]),
        ],
    },
    {
        "name": "Digital Omnichannel Shopper",
        "core": [
            ("Preferred_Shopping_Channel", ["Online", "Mobile"]),
            ("Mobile_App_User", ["Y"]),
        ],
        "optional": [
            ("Beauty_App_User", ["Y"]),
            ("Virtual_Try_On_Tool_Usage", ["Y"]),
            ("AI_Skin_Diagnostic_Tool_Usage", ["Y"]),
            ("Email_Engagement_Level", ["High"]),
            ("Wishlist_User", ["Y"]),
            ("Primary_Device_Type", None),
            ("Cross_Device_Shopper", ["Y"]),
            ("Direct_To_Consumer_Website_Shopper", ["Y"]),
            ("Online_Shopping_Frequency", ["Weekly", "Monthly"]),
            ("Beauty_Retailer_App_Push_Notification_OptIn", ["Y"]),
            ("Newsletter_Subscriber", ["Y"]),
            ("Household_Internet_Speed_Tier", None),
        ],
    },
    {
        "name": "Young Family Moms",
        "core": [
            ("Life_Stage", ["FamilyYoungKids"]),
            ("Gender", ["F"]),
        ],
        "optional": [
            ("Number_Of_Children", ["1", "2", "3"]),
            ("Married", ["Y"]),
            ("Household_Size", ["3", "4", "5"]),
            ("Generation_Cohort", ["Millennial"]),
            ("Travel_Size_Product_Preference", ["Y"]),
            ("Coupon_Usage_Frequency", ["Often", "Sometimes"]),
            ("Baby_Care_Product_Buyer", ["Y"]),
            ("Post_Partum_Skincare_Interest", ["Y"]),
            ("Multi_Use_Product_Preference", ["Y"]),
            ("Preferred_Contact_Time", ["Evening", "Weekend"]),
            ("Homeownership_Status", ["Own", "Rent"]),
            ("Employment_Status", ["FullTime", "HomeMaker", "PartTime"]),
        ],
    },
    {
        "name": "K-Beauty and J-Beauty Trend Follower",
        "core": [
            ("K_Beauty_Trend_Follower", ["Y"]),
            ("Skincare_Routine_Step_Count", ["6", "7", "8", "9", "10"]),
        ],
        "optional": [
            ("J_Beauty_Trend_Follower", ["Y"]),
            ("Mask_Treatment_Purchase_Frequency", ["Weekly", "Monthly"]),
            ("Beauty_Ingredient_Research_Behavior", ["Always", "Sometimes"]),
            ("Primary_Social_Platform", ["Instagram", "YouTube"]),
            ("Generation_Cohort", ["Millennial", "GenZ"]),
            ("Beauty_Fridge_Owner", ["Y"]),
            ("Niacinamide_Product_User", ["Y"]),
            ("Ethnicity_Group", ["Asian"]),
            ("New_Product_Early_Adopter_Score", ["7", "8", "9"]),
            ("Specialty_Beauty_Retailer_Shopper", ["Y"]),
            ("Exfoliation_Purchase_Frequency", ["Monthly", "Weekly"]),
        ],
    },
    {
        "name": "Med-Spa and Clinical Treatment",
        "core": [
            ("Med_Spa_Treatment_User", ["Y"]),
            ("Clinical_Skincare_Interest", ["Y"]),
        ],
        "optional": [
            ("Botox_Filler_Interest", ["Y"]),
            ("Facial_Treatment_Service_User", ["Y"]),
            ("SkinCeuticals_Purchase_History", ["Y"]),
            ("Household_Income_Range", ["150K-200K", "200K-300K", "300K+"]),
            ("LED_Skincare_Device_Owner", ["Y"]),
            ("Skin_Concern_Primary", ["Aging", "Hyperpigmentation"]),
            ("Microcurrent_Device_Owner", ["Y"]),
            ("Dermocosmetics_Buyer", ["Y"]),
            ("Eyebrow_Grooming_Service_User", ["Y"]),
            ("Eyelash_Extension_User", ["Y"]),
            ("Waxing_Hair_Removal_Service_User", ["Y"]),
            ("Occupation_Category", ["WhiteCollar", "SelfEmployed"]),
        ],
    },
    {
        "name": "Pregnancy-Safe Ingredient Seeker",
        "core": [
            ("Pregnancy_Safe_Product_Interest", ["Y"]),
            ("Gender", ["F"]),
        ],
        "optional": [
            ("Life_Stage", ["YoungCouple", "FamilyYoungKids"]),
            ("Clean_Beauty_Preference", ["Y"]),
            ("Beauty_Ingredient_Research_Behavior", ["Always"]),
            ("Generation_Cohort", ["Millennial"]),
            ("Married", ["Y"]),
            ("Post_Partum_Skincare_Interest", ["Y"]),
            ("Fragrance_Free_Product_Preference", ["Y"]),
            ("Hypoallergenic_Product_Preference", ["Y"]),
            ("Baby_Care_Product_Buyer", ["Y"]),
            ("Vegan_Beauty_Product_Preference", ["Y"]),
            ("Skin_Concern_Primary", ["Hyperpigmentation", "Sensitivity"]),
        ],
    },
    {
        "name": "Mature Skin Boomer",
        "core": [
            ("Generation_Cohort", ["BabyBoomer", "GenX"]),
            ("Mature_Skin_Product_Buyer", ["Y"]),
        ],
        "optional": [
            ("Life_Stage", ["EmptyNester", "Retiree"]),
            ("Skin_Concern_Primary", ["Aging", "Dryness"]),
            ("Moisturizer_Spend_Tier", ["High", "Premium"]),
            ("Department_Store_Beauty_Counter_Shopper", ["Y"]),
            ("Preferred_Shopping_Channel", ["InStore"]),
            ("Menopause_Skincare_Interest", ["Y"]),
            ("Collagen_Supplement_User", ["Y"]),
            ("Employment_Status", ["Retired", "FullTime"]),
            ("Homeownership_Status", ["Own"]),
            ("QVC_HSN_Beauty_Shopper", ["Y"]),
            ("Beauty_Counter_Employee_Interaction_Frequency", ["Frequently", "Occasionally"]),
            ("Age", ["58", "62", "66", "72", "76", "78"]),
        ],
    },
    {
        "name": "Heavy Makeup User",
        "core": [
            ("Makeup_Usage_Frequency", ["Daily"]),
            ("Makeup_Spend_Tier", ["Premium", "High"]),
        ],
        "optional": [
            ("Makeup_Purchase_Frequency", ["Monthly", "Weekly"]),
            ("Long_Wear_Makeup_Preference", ["Y"]),
            ("Foundation_Category_Purchase_Frequency", ["Monthly"]),
            ("Giorgio_Armani_Beauty_Purchase_History", ["Y"]),
            ("Custom_Foundation_Match_Service_User", ["Y"]),
            ("Color_Cosmetics_Shade_Range_Needed", None),
            ("Eye_Makeup_Purchase_Frequency", ["Weekly", "Monthly"]),
            ("Lip_Product_Purchase_Frequency", ["Monthly"]),
            ("Matte_Vs_Dewy_Finish_Preference", None),
            ("IT_Cosmetics_Purchase_History", ["Y"]),
            ("Urban_Decay_Purchase_History", ["Y"]),
            ("Special_Occasion_Makeup_Service_User", ["Y"]),
            ("Blush_Bronzer_Category_Spend_Tier", ["High", "Premium"]),
        ],
    },
    {
        "name": "Beauty Content Creator",
        "core": [
            ("User_Generated_Content_Creator", ["Y"]),
            ("Beauty_Content_Creation_Frequency", ["Frequently", "Occasionally"]),
        ],
        "optional": [
            ("Review_Writer", ["Y"]),
            ("Beauty_Influencer_Follower_Count_Tier", ["High", "Medium"]),
            ("Primary_Social_Platform", ["TikTok", "Instagram", "YouTube"]),
            ("Beauty_Product_Unboxing_Content_Viewer", ["Y"]),
            ("Limited_Edition_Collection_Buyer", ["Y"]),
            ("Livestream_Shopping_Beauty_Buyer", ["Y"]),
            ("New_Product_Early_Adopter_Score", ["8", "9", "10"]),
            ("Beauty_Masterclass_Webinar_Attendance", ["Y"]),
            ("Beauty_Subscription_Box_Member", ["Y"]),
            ("Cross_Category_Beauty_Buyer", ["Y"]),
            ("Beauty_Review_Site_Visitor", ["Y"]),
        ],
    },
    {
        "name": "Frequent Traveler Duty-Free",
        "core": [
            ("Travel_Frequency", ["Frequently"]),
            ("Duty_Free_Beauty_Shopper", ["Y"]),
        ],
        "optional": [
            ("Travel_Size_Product_Preference", ["Y"]),
            ("Household_Income_Range", ["100K-150K", "150K-200K", "200K-300K"]),
            ("Occupation_Category", ["WhiteCollar", "Technology", "SelfEmployed"]),
            ("Fragrance_Spend_Tier", ["Premium", "High"]),
            ("Preferred_Shopping_Channel", ["Hybrid"]),
            ("Duty_Beauty_Travel_Retail_Shopper", ["Y"]),
            ("Prada_Beauty_Purchase_History", ["Y"]),
            ("YSL_Beauty_Purchase_History", ["Y"]),
            ("Multi_Use_Product_Preference", ["Y"]),
            ("Streaming_Service_Subscriber", ["Y"]),
            ("Primary_Language", ["English", "Mandarin", "Spanish", "French"]),
            ("Time_Zone", None),
        ],
    },
]

# Neutral targeting filters real campaigns often carry. Every value fits any
# theme, so they add combination variety without distorting the co-occurrence.
NEUTRAL_ATTRIBUTES: list[tuple[str, list[str] | None]] = [
    ("Region", None),
    ("Urban_Rural_Classification", None),
    ("Preferred_Payment_Method", None),
    ("Referral_Source", None),
    ("Preferred_Contact_Time", None),
    ("Primary_Device_Type", None),
    ("Seasonal_Shopper_Type", None),
    ("Marital_Status_Detail", None),
]

# Curated labels for the attributes that show up most in audience names.
VALUE_LABELS: dict[tuple[str, str], str] = {
    ("Household_Income_Range", "Under30K"): "Under 30K Income",
    ("Household_Income_Range", "30K-50K"): "30-50K Income",
    ("Household_Income_Range", "50K-75K"): "50-75K Income",
    ("Household_Income_Range", "75K-100K"): "75-100K Income",
    ("Household_Income_Range", "100K-150K"): "100-150K Income",
    ("Household_Income_Range", "150K-200K"): "150-200K Income",
    ("Household_Income_Range", "200K-300K"): "200-300K Income",
    ("Household_Income_Range", "300K+"): "300K Plus Income",
    ("Generation_Cohort", "GenZ"): "Gen Z",
    ("Generation_Cohort", "Millennial"): "Millennial",
    ("Generation_Cohort", "GenX"): "Gen X",
    ("Generation_Cohort", "BabyBoomer"): "Baby Boomer",
    ("Gender", "F"): "Female",
    ("Gender", "M"): "Male",
    ("Skin_Concern_Primary", "Aging"): "Aging Concern",
    ("Skin_Concern_Primary", "Acne"): "Acne Concern",
    ("Skin_Concern_Primary", "Dryness"): "Dryness Concern",
    ("Skin_Concern_Primary", "Sensitivity"): "Sensitivity Concern",
    ("Skin_Concern_Primary", "Redness"): "Redness Concern",
    ("Skin_Concern_Primary", "Hyperpigmentation"): "Pigmentation Concern",
    ("Skin_Concern_Primary", "None"): "No Skin Concern",
    ("Skin_Type", "Sensitive"): "Sensitive Skin",
    ("Skin_Type", "Oily"): "Oily Skin",
    ("Skin_Type", "Combination"): "Combination Skin",
    ("Skincare_Spend_Tier", "Premium"): "Premium Skincare Spend",
    ("Skincare_Spend_Tier", "High"): "High Skincare Spend",
    ("Makeup_Spend_Tier", "Premium"): "Premium Makeup Spend",
    ("Prestige_Beauty_Spend_Tier", "Premium"): "Premium Prestige Spend",
    ("Beauty_Loyalty_Tier_Status", "Platinum"): "Platinum Tier",
    ("Beauty_Loyalty_Tier_Status", "Gold"): "Gold Tier",
    ("Primary_Social_Platform", "TikTok"): "TikTok Heavy",
    ("Primary_Social_Platform", "Instagram"): "Instagram Heavy",
    ("Primary_Social_Platform", "YouTube"): "YouTube Heavy",
    ("Preferred_Shopping_Channel", "Online"): "Online Buyer",
    ("Preferred_Shopping_Channel", "Mobile"): "Mobile Buyer",
    ("Preferred_Shopping_Channel", "InStore"): "In-Store Buyer",
    ("Preferred_Shopping_Channel", "Hybrid"): "Omnichannel Buyer",
    ("Life_Stage", "FamilyYoungKids"): "Young Kids Family",
    ("Life_Stage", "EmptyNester"): "Empty Nester",
    ("Life_Stage", "Retiree"): "Retiree",
    ("Life_Stage", "YoungSingle"): "Young Single",
    ("Life_Stage", "YoungCouple"): "Young Couple",
    ("Price_Sensitivity_Level", "High"): "Price Sensitive",
    ("Hair_Type", "Curly"): "Curly Hair",
    ("Hair_Type", "Coily"): "Coily Hair",
    ("Hair_Concern_Primary", "Dandruff"): "Dandruff Concern",
    ("Hair_Concern_Primary", "HairLoss"): "Hair Loss Concern",
    ("Hair_Concern_Primary", "Damage"): "Damaged Hair",
    ("Hair_Concern_Primary", "Frizz"): "Frizz Concern",
    ("Hair_Concern_Primary", "ColorFading"): "Color Fading Concern",
    ("Travel_Frequency", "Frequently"): "Frequent Traveler",
    ("Preferred_Fragrance_Family", "Floral"): "Floral Scent",
    ("Preferred_Fragrance_Family", "Woody"): "Woody Scent",
    ("Preferred_Fragrance_Family", "Citrus"): "Citrus Scent",
    ("Preferred_Fragrance_Family", "Fresh"): "Fresh Scent",
    ("Preferred_Fragrance_Family", "Oriental"): "Oriental Scent",
    ("Preferred_Fragrance_Family", "Gourmand"): "Gourmand Scent",
    ("Occupation_Category", "WhiteCollar"): "White Collar",
    ("Occupation_Category", "Technology"): "Tech Professional",
    ("Occupation_Category", "Healthcare"): "Healthcare Professional",
    ("Occupation_Category", "SelfEmployed"): "Self Employed",
    ("Employment_Status", "Student"): "Student",
    ("Employment_Status", "Retired"): "Retired",
    ("Education_Level", "Master"): "Masters Degree",
    ("Education_Level", "Doctorate"): "Doctorate",
    ("Ethnicity_Group", "Asian"): "Asian",
    ("Region", "West"): "West Region",
    ("Region", "South"): "South Region",
    ("Region", "Midwest"): "Midwest Region",
    ("Region", "Northeast"): "Northeast Region",
    ("Urban_Rural_Classification", "Urban"): "Urban",
    ("Urban_Rural_Classification", "Suburban"): "Suburban",
    ("Urban_Rural_Classification", "Rural"): "Rural",
    ("Seasonal_Shopper_Type", "SummerShopper"): "Summer Shopper",
    ("Seasonal_Shopper_Type", "YearRound"): "Year-Round Shopper",
    ("Homeownership_Status", "Own"): "Homeowner",
    ("Homeownership_Status", "Rent"): "Renter",
    ("Primary_Device_Type", "iOS"): "iOS Device",
    ("Primary_Device_Type", "Android"): "Android Device",
    ("Primary_Device_Type", "Desktop"): "Desktop User",
    ("Primary_Device_Type", "Tablet"): "Tablet User",
    ("Matte_Vs_Dewy_Finish_Preference", "NoPreference"): "No Finish Preference",
    ("SPF_Preference_Level", "NoPreference"): "No SPF Preference",
}

# Trim boilerplate off attribute names used as label prefixes.
ATTR_SUFFIX_TRIM: list[tuple[str, str]] = [
    ("_Category_Purchase_Frequency", " Purchase"),
    ("_Category_Spend_Tier", " Spend"),
    ("_Purchase_Frequency", " Purchase"),
    ("_Usage_Frequency", " Usage"),
    ("_Spend_Tier", " Spend"),
    ("_Engagement_Level", " Engagement"),
    ("_Concern_Primary", " Concern"),
    ("_Tier_Status", " Tier"),
    ("_Balance_Tier", " Points"),
    ("_Preference_Level", ""),
    ("_Classification", ""),
    ("_Frequency", ""),
    ("_Range", ""),
    ("_Level", ""),
    ("_Score", ""),
    ("_Band", ""),
    ("_Status", ""),
]


# Y/N flags whose attribute name is too long to read well inside an audience name.
FLAG_LABELS: dict[str, str] = {
    "Dermatologist_Recommended_Product_Preference": "Derm Recommended",
    "Beauty_Product_Ingredient_Allergy_Flag": "Ingredient Allergy",
    "Beauty_Product_Unboxing_Content_Viewer": "Unboxing Viewer",
    "Beauty_Retailer_App_Push_Notification_OptIn": "Push Opt-In",
    "Custom_Foundation_Match_Service_User": "Custom Shade Match",
    "Beauty_Masterclass_Webinar_Attendance": "Masterclass Attendee",
    "Department_Store_Beauty_Counter_Shopper": "Department Store Shopper",
    "Specialty_Beauty_Retailer_Shopper": "Specialty Retailer Shopper",
    "Direct_To_Consumer_Website_Shopper": "DTC Shopper",
    "Duty_Beauty_Travel_Retail_Shopper": "Travel Retail Shopper",
    "Personalized_Beauty_Product_Interest": "Personalized Products",
    "Hypoallergenic_Product_Preference": "Hypoallergenic",
    "Fragrance_Free_Product_Preference": "Fragrance Free",
    "Sustainable_Packaging_Preference": "Sustainable Packaging",
    "Vegan_Beauty_Product_Preference": "Vegan Beauty",
    "Gluten_Free_Beauty_Preference": "Gluten Free",
    "Multi_Use_Product_Preference": "Multi-Use Products",
    "Travel_Size_Product_Preference": "Travel Size",
    "Clean_Beauty_Preference": "Clean Beauty",
    "Cruelty_Free_Preference": "Cruelty Free",
    "Long_Wear_Makeup_Preference": "Long Wear Makeup",
    "In_Store_Beauty_Consultation_User": "In-Store Consultation",
    "Waxing_Hair_Removal_Service_User": "Waxing Service",
    "Special_Occasion_Makeup_Service_User": "Occasion Makeup Service",
    "Curling_Iron_Straightener_Owner": "Styling Iron Owner",
    "Wellness_Beauty_Supplement_User": "Wellness Supplements",
    "Pregnancy_Safe_Product_Interest": "Pregnancy Safe",
    "Post_Partum_Skincare_Interest": "Post-Partum Skincare",
    "Menopause_Skincare_Interest": "Menopause Skincare",
    "Skin_Diagnostic_Quiz_Completion": "Skin Quiz Taken",
    "AI_Skin_Diagnostic_Tool_Usage": "AI Skin Diagnostic",
    "Beauty_Device_Owner_Facial_Cleansing": "Cleansing Device Owner",
}


def humanize(token: str) -> str:
    text = token.replace("_", " ")
    # Split camelCase but leave acronyms intact, so iOS does not become "i OS".
    text = re.sub(r"(?<=[a-z0-9])(?=[A-Z][a-z])", " ", text)
    text = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", " ", text)
    return " ".join(text.split())


def short_attribute(attribute: str) -> str:
    for suffix, replacement in ATTR_SUFFIX_TRIM:
        if attribute.endswith(suffix):
            return humanize(attribute[: -len(suffix)] + replacement)
    return humanize(attribute)


def segment_label(attribute: str, value: str) -> str:
    """Turn an (attribute, value) pair into a short human label.

    Values are always kept with their attribute unless a curated label exists.
    Bare values read as nonsense on their own — "Often", "Poor", "Extended" say
    nothing without knowing which attribute they came from.
    """
    curated = VALUE_LABELS.get((attribute, value))
    if curated:
        return curated
    if attribute.endswith("_Purchase_History"):
        brand = humanize(attribute[: -len("_Purchase_History")])
        return f"{brand} Buyer" if value == "Y" else f"{brand} Non-Buyer"
    if attribute.endswith("_Preference_Score"):
        brand = humanize(attribute[: -len("_Preference_Score")])
        return f"{brand} Affinity {value}"
    if value in ("Y", "N"):
        base = FLAG_LABELS.get(attribute) or short_attribute(attribute)
        return base if value == "Y" else f"No {base}"
    return f"{short_attribute(attribute)} {humanize(value)}"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Generate themed CPG audiences")
    p.add_argument("--segments", type=Path, default=DATA_DIR / "segments_cpg_demo.csv")
    p.add_argument(
        "--output", type=Path, default=DATA_DIR / "audience_hack_cpg_demo.csv"
    )
    p.add_argument("--client-name", type=str, default="Hack CPG Demo")
    p.add_argument("--industry", type=str, default="CPG")
    p.add_argument("--audiences", type=int, default=2000)
    p.add_argument(
        "--audience-id-base",
        type=int,
        default=0,
        help="audience_id is this base plus a sequence, kept inside signed 64-bit range",
    )
    p.add_argument("--min-segments", type=int, default=3)
    p.add_argument("--max-segments", type=int, default=7)
    p.add_argument(
        "--max-name-parts",
        type=int,
        default=5,
        help="Upper bound on qualifiers appended to a name to keep it unique",
    )
    p.add_argument("--seed", type=int, default=20260819)
    p.add_argument("--as-of", type=str, default="2026-08-19")
    p.add_argument("--history-days", type=int, default=400)
    return p.parse_args()


def load_segments(
    path: Path,
) -> tuple[dict[tuple[str, str], tuple[str, str]], dict[str, list[str]]]:
    lookup: dict[tuple[str, str], tuple[str, str]] = {}
    by_attribute: dict[str, list[str]] = {}
    with path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            attribute, value = row["attribute_name"], row["node_value"]
            lookup[(attribute, value)] = (row["taxonomy_id"], row["segment_name"])
            by_attribute.setdefault(attribute, []).append(value)
    return lookup, by_attribute


def resolve_pairs(
    entries: list[tuple[str, list[str] | None]],
    lookup: dict[tuple[str, str], tuple[str, str]],
    by_attribute: dict[str, list[str]],
    context: str,
    dropped: list[str],
) -> list[tuple[str, list[str]]]:
    out: list[tuple[str, list[str]]] = []
    for attribute, values in entries:
        if attribute not in by_attribute:
            dropped.append(f"{context}: attribute {attribute}")
            continue
        allowed = values if values is not None else by_attribute[attribute]
        usable = [v for v in allowed if (attribute, v) in lookup]
        missing = sorted(set(allowed) - set(usable))
        if missing:
            dropped.append(f"{context}: {attribute} values {missing}")
        if usable:
            out.append((attribute, usable))
    return out


def resolve_themes(
    lookup: dict[tuple[str, str], tuple[str, str]],
    by_attribute: dict[str, list[str]],
) -> tuple[list[dict], list[tuple[str, list[str]]]]:
    dropped: list[str] = []
    resolved: list[dict] = []
    for theme in THEMES:
        out = {
            "name": theme["name"],
            "core": resolve_pairs(theme["core"], lookup, by_attribute, theme["name"], dropped),
            "optional": resolve_pairs(
                theme["optional"], lookup, by_attribute, theme["name"], dropped
            ),
        }
        if not out["core"]:
            raise SystemExit(f"Theme {theme['name']!r} has no usable core attributes")
        resolved.append(out)

    neutral = resolve_pairs(NEUTRAL_ATTRIBUTES, lookup, by_attribute, "neutral", dropped)

    if dropped:
        print(f"Skipped {len(dropped)} entries not present in the taxonomy:")
        for line in dropped:
            print(f"  - {line}")
    return resolved, neutral


def build_audience(
    rng: random.Random,
    theme: dict,
    neutral: list[tuple[str, list[str]]],
    lookup: dict[tuple[str, str], tuple[str, str]],
    min_segments: int,
    max_segments: int,
) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    """Returns (segments as (id, name), the non-core picks that distinguish it)."""
    core: list[tuple[str, str]] = [
        (attribute, rng.choice(values)) for attribute, values in theme["core"]
    ]
    extra: list[tuple[str, str]] = []

    optional = list(theme["optional"])
    rng.shuffle(optional)
    target = rng.randint(min_segments, max_segments)
    for attribute, values in optional:
        if len(core) + len(extra) >= target:
            break
        if any(a == attribute for a, _ in core + extra):
            continue
        extra.append((attribute, rng.choice(values)))

    for attribute, values in rng.sample(neutral, k=min(rng.randint(0, 2), len(neutral))):
        if any(a == attribute for a, _ in core + extra):
            continue
        extra.append((attribute, rng.choice(values)))

    return [lookup[key] for key in core + extra], extra


def audience_label(
    theme_name: str,
    distinguishing: list[tuple[str, str]],
    used_names: set[str],
    max_parts: int,
) -> str | None:
    """Shortest name that is not already taken, or None if the basket must be redrawn.

    Only non-core picks become qualifiers — core segments are what the theme name
    already says, so including them would just repeat it.
    """
    qualifiers: list[str] = []
    seen: set[str] = set()
    lowered_theme = theme_name.lower()
    for attribute, value in distinguishing:
        text = segment_label(attribute, value)
        key = text.lower()
        if not text or key in seen or key in lowered_theme:
            continue
        seen.add(key)
        qualifiers.append(text)

    if not qualifiers:
        return theme_name if theme_name not in used_names else None

    for count in range(1, min(len(qualifiers), max_parts) + 1):
        name = f"{theme_name} - {' and '.join(qualifiers[:count])}"
        if name not in used_names:
            return name
    return None


def main() -> None:
    args = parse_args()
    if not args.segments.exists():
        raise SystemExit(
            f"Segments file not found: {args.segments}\n"
            "Run scripts/parse_segment_mapping.py first."
        )
    if args.min_segments < 2 or args.max_segments < args.min_segments:
        raise SystemExit("Require 2 <= --min-segments <= --max-segments")

    max_id = args.audience_id_base + args.audiences
    if max_id > 2**63 - 1:
        raise SystemExit(
            f"audience_id would overflow signed 64-bit: {max_id}. Lower --audience-id-base."
        )

    rng = random.Random(args.seed)
    lookup, by_attribute = load_segments(args.segments)
    themes, neutral = resolve_themes(lookup, by_attribute)
    print(f"Loaded {len(lookup)} segments, {len(themes)} themes, {len(neutral)} neutral attrs")

    as_of = date.fromisoformat(args.as_of)
    start = as_of - timedelta(days=args.history_days)

    rows: list[list[str]] = []
    used_segments: set[str] = set()
    used_names: set[str] = set()
    used_baskets: set[frozenset[str]] = set()
    attempts_used = 0

    for seq in range(1, args.audiences + 1):
        for attempt in range(1, 501):
            theme = rng.choice(themes)
            segments, distinguishing = build_audience(
                rng, theme, neutral, lookup, args.min_segments, args.max_segments
            )
            basket = frozenset(segment_id for segment_id, _ in segments)
            if basket in used_baskets:
                continue
            name = audience_label(
                theme["name"], distinguishing, used_names, args.max_name_parts
            )
            if name is None:
                continue
            used_baskets.add(basket)
            used_names.add(name)
            attempts_used += attempt
            break
        else:
            raise SystemExit(
                f"Could not find a unique audience for #{seq} after 500 tries. "
                "Lower --audiences or widen the theme pools."
            )

        audience_id = str(args.audience_id_base + seq)
        created = start + timedelta(days=rng.randint(0, args.history_days))
        # Newer audiences are more likely to have been pushed downstream.
        age_ratio = (as_of - created).days / max(args.history_days, 1)
        distributed = "true" if rng.random() < (0.75 if age_ratio < 0.3 else 0.45) else "false"

        for segment_id, segment_name in segments:
            used_segments.add(segment_id)
            rows.append(
                [
                    args.client_name,
                    args.industry,
                    audience_id,
                    name,
                    segment_id,
                    segment_name,
                    created.isoformat(),
                    distributed,
                ]
            )

    assert len(used_names) == args.audiences, "audience names are not unique"
    assert len(used_baskets) == args.audiences, "audience segment sets are not unique"

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(
            [
                "client_name",
                "industry",
                "audience_id",
                "audience_name",
                "segment_id",
                "segment_name",
                "created_at",
                "distributed",
            ]
        )
        writer.writerows(rows)

    print(f"Wrote {len(rows)} rows / {args.audiences} audiences → {args.output}")
    print(f"  distinct segments used : {len(used_segments)}")
    print(f"  distinct names         : {len(used_names)}")
    print(f"  avg segments/audience  : {len(rows) / args.audiences:.2f}")
    print(f"  avg draws per audience : {attempts_used / args.audiences:.2f}")


if __name__ == "__main__":
    main()
