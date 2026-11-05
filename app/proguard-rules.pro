# kotlinx.serialization: keep the generated serializers of the data classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class gr.wsol.billablecalls.** { *** Companion; *** serializer(...); }
-keep,includedescriptorclasses class gr.wsol.billablecalls.**$$serializer { *; }
