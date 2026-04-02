$sch = 'c:\Users\Vincent Santosa\Desktop\EPD 3D G6\kicad pcb\EPD 3D G6\EPD 3D G6.kicad_sch'
$enc = [System.Text.UTF8Encoding]::new($false)
$content = [System.IO.File]::ReadAllText($sch, $enc)

# Normalise line endings
$content = $content.Replace("`r`n", "`n")

# Remove all existing global_label blocks
$content = [System.Text.RegularExpressions.Regex]::Replace(
    $content,
    "\n\t\(global_label[\s\S]*?\n\t\)\n",
    "`n")

# Build a global_label block
function gl {
    param($name, $shape, $x, $y, $angle, $uuid)
    $j = if ($angle -eq 0) { "left" } else { "right" }
    $r = '${INTERSHEET_REFS}'
    return "`t(global_label `"$name`"`n`t`t(shape $shape)`n`t`t(at $x $y $angle)`n`t`t(fields_autoplaced yes)`n`t`t(effects`n`t`t`t(font`n`t`t`t`t(size 1.27 1.27)`n`t`t`t)`n`t`t`t(justify $j)`n`t`t)`n`t`t(uuid `"$uuid`")`n`t`t(property `"Intersheetrefs`" `"$r`"`n`t`t`t(at $x $y 0)`n`t`t`t(effects`n`t`t`t`t(font`n`t`t`t`t`t(size 1.27 1.27)`n`t`t`t`t)`n`t`t`t`t(hide yes)`n`t`t`t)`n`t`t)`n`t)`n"
}

# ─── BLOCK 1 – Power Input / Battery ───
$new  = gl "VBAT"        "output"  48  36   0 "aa000001-0000-0000-0000-000000000001"

# ─── BLOCK 2 – Boost Converter ───
$new += gl "VBAT"        "input"   60  36 180 "aa000002-0000-0000-0000-000000000001"
$new += gl "5V_SYS"      "output" 104  36   0 "aa000002-0000-0000-0000-000000000002"

# ─── BLOCK 3 – ESP32-C6 (power at top, inputs left, outputs right, 10mm step) ───
$new += gl "5V_SYS"      "input"  116  12 180 "aa000003-0000-0000-0000-000000000001"
$new += gl "3V3"         "output" 182  12   0 "aa000003-0000-0000-0000-000000000002"
# Left inputs  (GPIO ADC/sense/UI → ESP32)
$new += gl "FSR1_ADC"    "input"  116  30 180 "aa000003-0000-0000-0000-000000000003"
$new += gl "FSR2_ADC"    "input"  116  40 180 "aa000003-0000-0000-0000-000000000004"
$new += gl "FSR3_ADC"    "input"  116  50 180 "aa000003-0000-0000-0000-000000000005"
$new += gl "FSR4_ADC"    "input"  116  60 180 "aa000003-0000-0000-0000-000000000006"
$new += gl "VBAT_SENSE"  "input"  116  70 180 "aa000003-0000-0000-0000-000000000007"
$new += gl "MODE_BTN"    "input"  116  80 180 "aa000003-0000-0000-0000-000000000008"
$new += gl "CONN_DETECT" "input"  116  90 180 "aa000003-0000-0000-0000-000000000009"
# Right outputs (ESP32 → TMC / OLED)
$new += gl "STEPPER_ENN" "output" 182  30   0 "aa000003-0000-0000-0000-00000000000a"
$new += gl "ACT1_STEP"   "output" 182  40   0 "aa000003-0000-0000-0000-00000000000b"
$new += gl "ACT1_DIR"    "output" 182  50   0 "aa000003-0000-0000-0000-00000000000c"
$new += gl "ACT2_STEP"   "output" 182  60   0 "aa000003-0000-0000-0000-00000000000d"
$new += gl "ACT2_DIR"    "output" 182  70   0 "aa000003-0000-0000-0000-00000000000e"
$new += gl "ACT3_STEP"   "output" 182  80   0 "aa000003-0000-0000-0000-00000000000f"
$new += gl "ACT3_DIR"    "output" 182  90   0 "aa000003-0000-0000-0000-000000000010"
$new += gl "OLED_SDA"    "output" 182 100   0 "aa000003-0000-0000-0000-000000000011"
$new += gl "OLED_SCL"    "output" 182 110   0 "aa000003-0000-0000-0000-000000000012"

# ─── BLOCK 4A – TMC2209 #1 (ACT1), Y=2-62 ───
$new += gl "5V_SYS"      "input"  194  12 180 "aa000004-0000-0000-0000-000000000001"
$new += gl "3V3"         "input"  194  22 180 "aa000004-0000-0000-0000-000000000002"
$new += gl "STEPPER_ENN" "input"  194  32 180 "aa000004-0000-0000-0000-000000000003"
$new += gl "ACT1_STEP"   "input"  194  42 180 "aa000004-0000-0000-0000-000000000004"
$new += gl "ACT1_DIR"    "input"  194  52 180 "aa000004-0000-0000-0000-000000000005"

# ─── BLOCK 4B – TMC2209 #2 (ACT2), Y=66-126 ───
$new += gl "5V_SYS"      "input"  194  78 180 "aa000004-0000-0000-0000-000000000006"
$new += gl "3V3"         "input"  194  88 180 "aa000004-0000-0000-0000-000000000007"
$new += gl "STEPPER_ENN" "input"  194  98 180 "aa000004-0000-0000-0000-000000000008"
$new += gl "ACT2_STEP"   "input"  194 108 180 "aa000004-0000-0000-0000-000000000009"
$new += gl "ACT2_DIR"    "input"  194 118 180 "aa000004-0000-0000-0000-00000000000a"

# ─── BLOCK 4C – TMC2209 #3 (ACT3), Y=130-190 ───
$new += gl "5V_SYS"      "input"  194 142 180 "aa000004-0000-0000-0000-00000000000b"
$new += gl "3V3"         "input"  194 152 180 "aa000004-0000-0000-0000-00000000000c"
$new += gl "STEPPER_ENN" "input"  194 162 180 "aa000004-0000-0000-0000-00000000000d"
$new += gl "ACT3_STEP"   "input"  194 172 180 "aa000004-0000-0000-0000-00000000000e"
$new += gl "ACT3_DIR"    "input"  194 182 180 "aa000004-0000-0000-0000-00000000000f"

# ─── BLOCK 5 – FSR Analog Front-End, Y=60-170 ───
$new += gl "3V3"         "input"    4  75 180 "aa000005-0000-0000-0000-000000000001"
$new += gl "FSR1_ADC"    "output" 102 100   0 "aa000005-0000-0000-0000-000000000002"
$new += gl "FSR2_ADC"    "output" 102 115   0 "aa000005-0000-0000-0000-000000000003"
$new += gl "FSR3_ADC"    "output" 102 130   0 "aa000005-0000-0000-0000-000000000004"
$new += gl "FSR4_ADC"    "output" 102 145   0 "aa000005-0000-0000-0000-000000000005"

# ─── BLOCK 6 – VBAT Sense, Y=60-110 ───
$new += gl "VBAT"        "input"   58  85 180 "aa000006-0000-0000-0000-000000000001"
$new += gl "VBAT_SENSE"  "output" 102  85   0 "aa000006-0000-0000-0000-000000000002"

# ─── BLOCK 7 – OLED Display, Y=134-190 ───
$new += gl "3V3"         "input"  116 148 180 "aa000007-0000-0000-0000-000000000001"
$new += gl "OLED_SDA"    "input"  116 162 180 "aa000007-0000-0000-0000-000000000002"
$new += gl "OLED_SCL"    "input"  116 176 180 "aa000007-0000-0000-0000-000000000003"

# ─── BLOCK 8 – User Interface, Y=174-230 ───
$new += gl "MODE_BTN"    "output" 102 192   0 "aa000008-0000-0000-0000-000000000001"
$new += gl "CONN_DETECT" "output" 102 210   0 "aa000008-0000-0000-0000-000000000002"

# Insert all labels before (sheet_instances
if ($content -notmatch "\t\(sheet_instances") {
    Write-Error "Could not find (sheet_instances anchor — aborting."
    exit 1
}

$content = $content.Replace("`t(sheet_instances", $new + "`t(sheet_instances")
[System.IO.File]::WriteAllText($sch, $content, $enc)

$labelCount = ([regex]::Matches($new, "\(global_label")).Count
Write-Host "Done — $labelCount global labels written."
