# Gson reflects over these project-storage and ESX DTOs. Preserve their fields,
# enum constants, constructors and generic element types across R8 releases.
# Other application code and dependencies remain eligible for shrinking.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep @com.example.wiprober.JsonModel class * { *; }
