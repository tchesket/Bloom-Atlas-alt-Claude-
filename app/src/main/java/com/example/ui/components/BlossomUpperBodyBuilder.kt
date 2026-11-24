package com.example.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-resolution upper-body anatomical & facial geometry builders for Blossom v4.1:
 *  1. 10 Proportionate Relaxed 3D Fingers & Thumbs (exact outward CCW winding on both hands)
 *  2. Live-Deformable Soft Rounded Medium Breasts (exact outward CCW winding -> exact skin-tone
 *     match with body, natural supine teardrop contour, soft feathered areolae, rounded 3D nipples)
 *  3. Sculptural Realistic Feminine Head & Face (96x88 bottom-to-top outward CCW mesh -> seamless
 *     skin lighting & neck join, calm serene almond blue eyes, soft eyebrows, dainty nose & lips)
 *  4. Glossy Natural Sapphire-Blue 3D Eyes (calm relaxed gaze, upper eyelids grazing upper iris)
 *  5. Stylish Layered Dark-Brown / Soft-Black Pixie Haircut (dimensional crown + 24 sweeping locks)
 */
internal object BlossomUpperBodyBuilder {

    // Classical adult female head (1.08x scale -> balanced oval proportion) resting elevated on ergonomic pillow
    const val HEAD_CENTER_Y = 6.38f
    val HEAD_CENTER_Z: Float = BlossomBodyBuilder.mattressSurfaceZ(HEAD_CENTER_Y) + 1.24f
    private const val HEAD_SCALE = 1.08f
    private const val HEAD_PITCH_RAD = 0.09f
    private val COS_PITCH = cos(HEAD_PITCH_RAD)
    private val SIN_PITCH = sin(HEAD_PITCH_RAD)

    // Anatomically centered chest patch (0.62f from sternum -> organic Petite/Medium integration & natural Full cleavage!)
    const val BREAST_CENTER_AX = 0.62f
    const val BREAST_CENTER_Y = 3.92f
    const val BREAST_FOOTPRINT_R = 0.96f
    private const val BREAST_MEDIAL_R = 0.615f
    private const val BREAST_LATERAL_R = 0.86f
    private const val BREAST_VERTICAL_R = 1.02f
    const val BREAST_COLS = 64
    const val BREAST_ROWS = 60

    private fun headLocalToWorld(lx: Float, ly: Float, lz: Float): FloatArray {
        val sx = lx * HEAD_SCALE
        val sy = ly * HEAD_SCALE
        val sz = lz * HEAD_SCALE
        val wy = HEAD_CENTER_Y + sy * COS_PITCH - sz * SIN_PITCH
        val wz = HEAD_CENTER_Z + sy * SIN_PITCH + sz * COS_PITCH
        return floatArrayOf(sx, wy, wz)
    }

    private fun headNormalToWorld(nx: Float, ny: Float, nz: Float): FloatArray {
        val wy = ny * COS_PITCH - nz * SIN_PITCH
        val wz = ny * SIN_PITCH + nz * COS_PITCH
        val len = sqrt(nx * nx + wy * wy + wz * wz).coerceAtLeast(1e-5f)
        return floatArrayOf(nx / len, wy / len, wz / len)
    }

    internal data class BlossomFingerSpec(
        val rootAx: Float,
        val rootY: Float,
        val rootZ: Float,
        val length: Float,
        val rLat: Float,
        val rZ: Float,
        val yawOut: Float,
        val archLift: Float,
        val tipCurlDown: Float,
        val isThumb: Boolean = false
    )

    internal val BLOSSOM_FINGER_SPECS: List<BlossomFingerSpec> by lazy {
        val zPalm = BlossomBodyBuilder.mattressSurfaceZ(0.36f) + 0.185f
        // Harmonious feminine hand proportions: true opposable thumb with open V-web + sleek fingers seated on sculpted palm
        listOf(
            BlossomFingerSpec(rootAx = 2.13f, rootY = 0.56f, rootZ = zPalm - 0.016f, length = 0.445f, rLat = 0.062f, rZ = 0.056f, yawOut = -0.54f, archLift = 0.018f, tipCurlDown = 0.018f, isThumb = true),
            BlossomFingerSpec(rootAx = 2.165f, rootY = 0.02f, rootZ = zPalm + 0.008f, length = 0.435f, rLat = 0.059f, rZ = 0.053f, yawOut = -0.02f, archLift = 0.032f, tipCurlDown = 0.038f),
            BlossomFingerSpec(rootAx = 2.255f, rootY = -0.01f, rootZ = zPalm + 0.012f, length = 0.480f, rLat = 0.061f, rZ = 0.055f, yawOut = 0.01f, archLift = 0.035f, tipCurlDown = 0.042f),
            BlossomFingerSpec(rootAx = 2.345f, rootY = 0.02f, rootZ = zPalm + 0.008f, length = 0.430f, rLat = 0.057f, rZ = 0.051f, yawOut = 0.035f, archLift = 0.030f, tipCurlDown = 0.038f),
            BlossomFingerSpec(rootAx = 2.430f, rootY = 0.07f, rootZ = zPalm + 0.002f, length = 0.345f, rLat = 0.051f, rZ = 0.046f, yawOut = 0.07f, archLift = 0.024f, tipCurlDown = 0.032f)
        )
    }

    const val PALM_COLS = 26
    const val PALM_ROWS = 22
    const val FINGER_COLS = 16
    const val FINGER_ROWS = 18
    const val NAIL_COLS = 10
    const val NAIL_ROWS = 8

    internal data class ArticulatedFingerSpine(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val tanX: Float,
        val tanY: Float,
        val tanZ: Float,
        val latX: Float,
        val latY: Float,
        val latZ: Float,
        val dorsX: Float,
        val dorsY: Float,
        val dorsZ: Float
    )

    /**
     * Evaluates a realistic hinged phalanx skeleton in rest hand space:
     *  - Fingers: 3 straight rigid phalanx bones meeting at crisp MCP (s=0.12), PIP (s=0.48), and DIP (s=0.75) knuckles
     *  - Thumb: 2 straight rigid phalanx bones (CMC/MCP @ s=0.14, IP @ s=0.58) with true opposable pronation roll (~42-56 deg)
     *    so the thumbpad opposes naturally toward the fingers and the thumbnail sits dorso-medially!
     */
    internal fun evaluateArticulatedFingerSpine(
        spec: BlossomFingerSpec,
        side: Float,
        s: Float,
        flex: BlossomArmIkRig.FingerFlexPose
    ): ArticulatedFingerSpine {
        val effectiveYaw = spec.yawOut + flex.splayRad
        val fwdX = side * sin(effectiveYaw)
        val fwdY = -cos(effectiveYaw)
        val baseLatX = side * cos(effectiveYaw)
        val baseLatY = sin(effectiveYaw)

        // Thumb naturally pronates (opposes) around its long axis so its pad faces inward toward the index finger
        val thumbRoll = if (spec.isThumb) flex.rollRad else 0f
        // Moderate opposition flexion plane so thumb curves gently anteriorly/medially without folding sideways into palm
        val flexPlaneRoll = if (spec.isThumb) thumbRoll * 0.28f else 0f
        val cFP = cos(flexPlaneRoll)
        val sFP = sin(flexPlaneRoll)
        // Flexion "down" direction in rest hand space (for fingers: (0,0,-1); for thumb: tilted medially-inward!)
        val flexDownX = baseLatX * sFP
        val flexDownY = baseLatY * sFP
        val flexDownZ = -cFP
        // Orthogonal dorsal axis in the flexion plane
        val flexUpX = -flexDownX
        val flexUpY = -flexDownY
        val flexUpZ = -flexDownZ
        // Orthogonal lateral axis perpendicular to the flexion plane
        val flexLatX = baseLatX * cFP
        val flexLatY = baseLatY * cFP
        val flexLatZ = sFP

        fun jointPitchAt(u: Float): Float {
            return if (spec.isThumb) {
                val cmcW = smoothStep(0.08f, 0.22f, u)
                val ipW = smoothStep(0.51f, 0.65f, u)
                flex.mcpFlexRad * cmcW + flex.pipFlexRad * ipW
            } else {
                // Crisp narrow hinge windows -> straight rigid proximal, middle, & distal phalanx bones!
                val mcpW = smoothStep(0.07f, 0.17f, u)
                val pipW = smoothStep(0.43f, 0.53f, u)
                val dipW = smoothStep(0.70f, 0.80f, u)
                flex.mcpFlexRad * mcpW + flex.pipFlexRad * pipW + flex.dipFlexRad * dipW
            }
        }

        // Integrate rigid-phalanx spine with fine steps so crisp hinges stay accurate
        val alongRoot = if (spec.isThumb) -0.095f else -0.105f
        val totalLen = spec.length - alongRoot
        val steps = 24
        val ds = s / steps

        var cx = side * spec.rootAx + fwdX * alongRoot
        var cy = spec.rootY + fwdY * alongRoot
        var cz = spec.rootZ
        for (k in 0 until steps) {
            val sMid = (k + 0.5f) * ds
            val pitch = jointPitchAt(sMid)
            val segLen = totalLen * ds
            val cosP = cos(pitch)
            val sinP = sin(pitch)
            cx += (fwdX * cosP + flexDownX * sinP) * segLen
            cy += (fwdY * cosP + flexDownY * sinP) * segLen
            cz += (flexDownZ * sinP) * segLen
        }

        // Exact orthonormal (tan, lat, dors) frame at parameter s
        val pitchAtS = jointPitchAt(s)
        val cosP = cos(pitchAtS)
        val sinP = sin(pitchAtS)

        val tanX = fwdX * cosP + flexDownX * sinP
        val tanY = fwdY * cosP + flexDownY * sinP
        val tanZ = flexDownZ * sinP

        val unrolledDorsX = flexUpX * cosP + fwdX * sinP
        val unrolledDorsY = flexUpY * cosP + fwdY * sinP
        val unrolledDorsZ = flexUpZ * cosP

        // Remaining axial pronation roll on the thumb so thumbnail faces dorso-medially and pad opposes the fingers
        val remRoll = thumbRoll - flexPlaneRoll
        val cR = cos(remRoll)
        val sR = sin(remRoll)
        val latX = flexLatX * cR + unrolledDorsX * sR
        val latY = flexLatY * cR + unrolledDorsY * sR
        val latZ = flexLatZ * cR + unrolledDorsZ * sR
        val dorsX = unrolledDorsX * cR - flexLatX * sR
        val dorsY = unrolledDorsY * cR - flexLatY * sR
        val dorsZ = unrolledDorsZ * cR - flexLatZ * sR

        val restArchScale = 1f - smoothStep(0f, 0.45f, abs(flex.pipFlexRad) + abs(flex.mcpFlexRad)) * 0.72f
        val restArchZ = (spec.archLift * sin(s * PI.toFloat()) - spec.tipCurlDown * smoothStep(0.36f, 1.0f, s)) * restArchScale
        val medialCurve = if (spec.isThumb) {
            0.018f * smoothStep(0.28f, 1.0f, s)
        } else {
            -0.010f * smoothStep(0.32f, 1.0f, s)
        }

        cx += baseLatX * medialCurve
        cy += baseLatY * medialCurve
        cz += restArchZ

        return ArticulatedFingerSpine(
            cx = cx, cy = cy, cz = cz,
            tanX = tanX, tanY = tanY, tanZ = tanZ,
            latX = latX, latY = latY, latZ = latZ,
            dorsX = dorsX, dorsY = dorsY, dorsZ = dorsZ
        )
    }

    private fun fingerRadiusProfile(s: Float, isThumb: Boolean = false): Pair<Float, Float> {
        // Plump, softly rounded human fingertip dome starting only at s=0.89 (never pointy/spiky!)
        val tipT = smoothStep(0.89f, 1.0f, s)
        val tipAngle = tipT * (PI.toFloat() * 0.5f)
        val tipScale = cos(tipAngle).coerceAtLeast(0.03f)

        val rootFlare = if (isThumb) {
            1f + 0.24f * (1f - smoothStep(0f, 0.30f, s))
        } else {
            1f + 0.16f * (1f - smoothStep(0f, 0.22f, s))
        }
        val distalTaper = 1f - (if (isThumb) 0.12f else 0.14f) * smoothStep(0.14f, 0.88f, s)
        val jointProfile = if (isThumb) {
            // Single sculpted thumb IP knuckle at s=0.58 + fleshy distal thumbpad
            val ipJoint = 1f + 0.082f * exp(-((s - 0.58f) / 0.068f).let { it * it })
            val padFull = 1f + 0.055f * exp(-((s - 0.80f) / 0.11f).let { it * it })
            ipJoint * padFull
        } else {
            // Distinct anatomical PIP (s=0.48) & DIP (s=0.75) knuckles + slight phalanx waisting between joints
            val pipJoint = 1f + 0.092f * exp(-((s - 0.48f) / 0.058f).let { it * it })
            val dipJoint = 1f + 0.060f * exp(-((s - 0.75f) / 0.052f).let { it * it })
            val padFull = 1f + 0.050f * exp(-((s - 0.86f) / 0.09f).let { it * it })
            pipJoint * dipJoint * padFull
        }
        return Pair(rootFlare * distalTaper * jointProfile * tipScale, tipAngle)
    }

    // -------------------------------------------------------------------------
    // 1A. HIGH-RESOLUTION SCULPTED PARAMETRIC PALMS (26x22 PER HAND, OUTWARD CCW)
    //     Replaces coarse voxelized SDF palm lumps with flat anatomical dorsum,
    //     4 delicate MCP knuckles, concave palmar cup, thenar/hypothenar pads,
    //     and seamless wrist-collar blending!
    // -------------------------------------------------------------------------
    private class PalmRestCache(
        val restData: FloatArray,
        val sides: FloatArray,
        val wristBlend: FloatArray
    )

    private val palmRestCache: PalmRestCache by lazy { buildPalmRestCache() }

    private fun buildPalmRestCache(): PalmRestCache {
        val cols = PALM_COLS
        val rows = PALM_ROWS
        val vertsPerPalm = cols * rows
        val totalVerts = vertsPerPalm * 2
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val restData = FloatArray(totalVerts * stride)
        val sideArr = FloatArray(totalVerts)
        val wristBlendArr = FloatArray(totalVerts)

        val zWrist = BlossomBodyBuilder.mattressSurfaceZ(0.82f) + 0.195f
        val zPalm = BlossomBodyBuilder.mattressSurfaceZ(0.36f) + 0.185f
        val floorZ = BlossomBodyBuilder.MATTRESS_TOP_Z + 0.015f
        val sides = floatArrayOf(-1f, +1f)
        val sideBuf = FloatArray(vertsPerPalm * stride)

        for ((handIdx, side) in sides.withIndex()) {
            val baseVert = handIdx * vertsPerPalm
            for (r in 0 until rows) {
                val s = r.toFloat() / (rows - 1)
                val capT = smoothStep(0.88f, 1.0f, s)
                val capAngle = capT * (PI.toFloat() * 0.5f)
                val capScale = cos(capAngle).coerceAtLeast(0.03f)
                val wristTuck = 0.94f + 0.06f * smoothStep(0.0f, 0.16f, s)

                val cxAx = 2.240f + 0.058f * smoothStep(0.0f, 0.85f, s)
                val cyBase = 0.88f - s * 0.935f
                val czBase = zWrist * (1f - s) + (zPalm + 0.004f) * s

                // Graceful tapered feminine palm silhouette (slender wrist -> organic arched metacarpal fan -> softly rounded knuckle corners, zero boxy rectangle!)
                val wristPinch = -0.010f * exp(-((s - 0.14f) / 0.10f).let { it * it })
                val halfW = (0.160f + wristPinch + 0.048f * smoothStep(0.14f, 0.72f, s) - 0.026f * smoothStep(0.72f, 0.94f, s)) * wristTuck * capScale
                val halfZ = (0.124f - 0.020f * smoothStep(0.08f, 0.46f, s) - 0.014f * smoothStep(0.56f, 0.88f, s)) * wristTuck * capScale
                val flatMix = smoothStep(0.16f, 0.46f, s)

                for (c in 0 until cols) {
                    val u = c.toFloat() / (cols - 1)
                    val phi = u * (2f * PI.toFloat())
                    val cosPhi = cos(phi)
                    val sinPhi = sin(phi)

                    val cSign = if (cosPhi >= 0f) 1f else -1f
                    val sSign = if (sinPhi >= 0f) 1f else -1f
                    val sqW = cSign * Math.pow(abs(cosPhi).toDouble().coerceAtLeast(1e-5), 0.90).toFloat()
                    val sqZ = sSign * Math.pow(abs(sinPhi).toDouble().coerceAtLeast(1e-5), 0.91).toFloat()
                    val latShape = cosPhi * (1f - flatMix) + sqW * flatMix
                    val zShape = sinPhi * (1f - flatMix) + sqZ * flatMix

                    var localLat = halfW * latShape
                    var localZ = halfZ * zShape
                    val palmEnv = (1f - capT) * smoothStep(0.04f, 0.22f, s)

                    // 1. Natural curved distal MCP knuckle arch peaking at middle finger and rounding softly down on both index & pinky edges
                    val archDist = cosPhi + 0.18f
                    val knuckleSlantY = (0.026f * archDist * archDist + 0.014f * cosPhi + 0.012f * smoothStep(0.32f, 0.95f, cosPhi)) *
                        smoothStep(0.44f, 0.94f, s)

                    // 2. Sculpted fleshy Thenar Eminence (thumb base cushion on radial-palmar border)
                    val thenarS = exp(-((s - 0.36f) / 0.19f).let { it * it })
                    val thenarRad = smoothStep(-0.08f, -0.82f, cosPhi)
                    val thenarPalmar = smoothStep(0.10f, -0.78f, sinPhi)
                    localLat -= 0.026f * thenarS * thenarRad * palmEnv
                    localZ -= 0.024f * thenarS * thenarRad * thenarPalmar * palmEnv

                    // 3. Sculpted Hypothenar heel pad (ulnar-palmar border) & distal metacarpal ball
                    val hypoS = exp(-((s - 0.40f) / 0.22f).let { it * it })
                    val hypoUlnar = smoothStep(0.18f, 0.84f, cosPhi) * smoothStep(0.05f, -0.75f, sinPhi)
                    val distalBall = exp(-((s - 0.80f) / 0.11f).let { it * it }) * smoothStep(-0.10f, -0.82f, sinPhi) *
                        (1f - smoothStep(0.75f, 1.0f, abs(cosPhi)))
                    localZ -= (0.015f * hypoS * hypoUlnar + 0.014f * distalBall) * palmEnv

                    // 4. Subtle concave palmar hollow framed between thenar, hypothenar, and metacarpal ball
                    val palmCup = exp(-((s - 0.56f) / 0.18f).let { it * it }) *
                        exp(-(cosPhi / 0.46f).let { it * it }) *
                        smoothStep(-0.25f, -0.88f, sinPhi)
                    localZ += 0.008f * palmCup * palmEnv

                    // 5. Sleek flat back of hand (dorsum) with 4 delicate MCP knuckle hills & subtle extensor tendons
                    val dorsMask = smoothStep(0.22f, 0.88f, sinPhi) * palmEnv
                    if (dorsMask > 0.001f) {
                        localZ -= 0.007f * dorsMask * smoothStep(0.22f, 0.72f, s)
                        val kCenters = floatArrayOf(-0.60f, -0.18f, 0.24f, 0.64f)
                        val kRowEnv = exp(-((s - 0.84f) / 0.072f).let { it * it })
                        val tendonSpan = smoothBell(s, 0.26f, 0.82f, 0.10f)
                        var knuckleLift = 0f
                        var tendonLift = 0f
                        for (kc in kCenters) {
                            val d = (cosPhi - kc) / 0.155f
                            val g = exp(-(d * d))
                            knuckleLift += g * kRowEnv
                            tendonLift += exp(-((cosPhi - kc) / 0.075f).let { it * it }) * tendonSpan
                        }
                        localZ += (0.011f * knuckleLift + 0.0030f * tendonLift) * dorsMask
                    }

                    val px = side * (cxAx + localLat)
                    val py = cyBase + knuckleSlantY
                    var pz = czBase + localZ
                    if (pz < floorZ) pz = floorZ

                    val palmAo = (1f - 0.16f * palmCup - 0.10f * smoothStep(0.20f, 0.90f, -sinPhi)).coerceIn(0.68f, 1f)
                    val palmPigment = (0.04f + 0.04f * smoothStep(0.30f, 0.90f, sinPhi) * smoothStep(0.65f, 0.90f, s)).coerceIn(0f, 0.22f)

                    val lIdx = (r * cols + c) * stride
                    sideBuf[lIdx] = px
                    sideBuf[lIdx + 1] = py
                    sideBuf[lIdx + 2] = pz
                    sideBuf[lIdx + 6] = 0.0f // skin
                    sideBuf[lIdx + 7] = 0.042f // satin skin sheen
                    sideBuf[lIdx + 8] = palmAo
                    sideBuf[lIdx + 9] = palmPigment

                    val globalVert = baseVert + r * cols + c
                    sideArr[globalVert] = side
                    // Match exact wrist blend window of BlossomArmIkRig (y=1.10 -> 0.54) so wrist collar NEVER separates!
                    wristBlendArr[globalVert] = smoothStep(1.10f, 0.54f, py)
                }
            }

            // Compute smooth wrapped normals (dP/dc x dP/dr is outward for side=+1, inward for side=-1)
            val flipSign = if (side < 0f) -1f else 1f
            for (r in 0 until rows) {
                val rPrev = max(0, r - 1)
                val rNext = min(rows - 1, r + 1)
                val s = r.toFloat() / (rows - 1)
                val wristNormBlend = 1f - smoothStep(0.0f, 0.24f, s)
                for (c in 0 until cols) {
                    val cPrev = if (c == 0) cols - 2 else c - 1
                    val cNext = if (c == cols - 1) 1 else c + 1
                    val iL = (r * cols + cPrev) * stride
                    val iR = (r * cols + cNext) * stride
                    val iD = (rPrev * cols + c) * stride
                    val iU = (rNext * cols + c) * stride

                    val tx = sideBuf[iR] - sideBuf[iL]
                    val ty = sideBuf[iR + 1] - sideBuf[iL + 1]
                    val tz = sideBuf[iR + 2] - sideBuf[iL + 2]
                    val bx = sideBuf[iU] - sideBuf[iD]
                    val by = sideBuf[iU + 1] - sideBuf[iD + 1]
                    val bz = sideBuf[iU + 2] - sideBuf[iD + 2]

                    var nx = (ty * bz - tz * by) * flipSign
                    var ny = (tz * bx - tx * bz) * flipSign
                    var nz = (tx * by - ty * bx) * flipSign
                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                    if (len > 1e-5f) {
                        nx /= len; ny /= len; nz /= len
                    } else {
                        nx = 0f; ny = -1f; nz = 0f
                    }

                    val lIdx = (r * cols + c) * stride
                    if (wristNormBlend > 0.001f) {
                        val sdfN = BlossomSdfBodyBuilder.evaluateBodyNormal(sideBuf[lIdx], sideBuf[lIdx + 1], sideBuf[lIdx + 2], eps = 0.018f)
                        val mx = nx * (1f - wristNormBlend) + sdfN[0] * wristNormBlend
                        val my = ny * (1f - wristNormBlend) + sdfN[1] * wristNormBlend
                        val mz = nz * (1f - wristNormBlend) + sdfN[2] * wristNormBlend
                        val mLen = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-5f)
                        nx = mx / mLen; ny = my / mLen; nz = mz / mLen
                    }
                    sideBuf[lIdx + 3] = nx
                    sideBuf[lIdx + 4] = ny
                    sideBuf[lIdx + 5] = nz
                }
            }

            System.arraycopy(sideBuf, 0, restData, baseVert * stride, sideBuf.size)
        }

        return PalmRestCache(restData = restData, sides = sideArr, wristBlend = wristBlendArr)
    }

    fun buildBlossomPalmsMesh(): DynamicGlMesh {
        val cols = PALM_COLS
        val rows = PALM_ROWS
        val vertsPerPalm = cols * rows
        val trisPerPalm = (cols - 1) * (rows - 1) * 6
        val mesh = DynamicGlMesh(vertsPerPalm * 2, trisPerPalm * 2)
        val indices = ShortArray(trisPerPalm * 2)
        val sides = floatArrayOf(-1f, +1f)
        var idxOut = 0
        for ((handIdx, side) in sides.withIndex()) {
            val baseVert = handIdx * vertsPerPalm
            for (r in 0 until rows - 1) {
                for (c in 0 until cols - 1) {
                    val i0 = (baseVert + r * cols + c).toShort()
                    val i1 = (baseVert + r * cols + c + 1).toShort()
                    val i2 = (baseVert + (r + 1) * cols + c).toShort()
                    val i3 = (baseVert + (r + 1) * cols + c + 1).toShort()
                    if (side > 0f) {
                        indices[idxOut++] = i0; indices[idxOut++] = i1; indices[idxOut++] = i2
                        indices[idxOut++] = i1; indices[idxOut++] = i3; indices[idxOut++] = i2
                    } else {
                        indices[idxOut++] = i0; indices[idxOut++] = i2; indices[idxOut++] = i1
                        indices[idxOut++] = i1; indices[idxOut++] = i2; indices[idxOut++] = i3
                    }
                }
            }
        }
        mesh.setIndices(indices)
        val leftRest = BlossomArmIkRig.solveSideArmIk(-1f, 0f, 0f)
        val rightRest = BlossomArmIkRig.solveSideArmIk(+1f, 0f, 0f)
        updateArticulatedPalmsMesh(mesh, leftRest, rightRest)
        return mesh
    }

    /**
     * Real-time 60 FPS update of Blossom's sculpted high-resolution parametric palms,
     * seamlessly matching the volume-preserving wrist blend of [BlossomArmIkRig].
     */
    fun updateArticulatedPalmsMesh(
        palmsMesh: DynamicGlMesh,
        leftSol: BlossomArmIkRig.SideArmIkSolution,
        rightSol: BlossomArmIkRig.SideArmIkSolution
    ) {
        val cache = palmRestCache
        val src = cache.restData
        val dst = palmsMesh.vertexData
        val sides = cache.sides
        val wHand = cache.wristBlend
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val totalVerts = palmsMesh.vertexCount

        if (leftSol.poseProgress <= 0.0001f && rightSol.poseProgress <= 0.0001f) {
            System.arraycopy(src, 0, dst, 0, src.size)
            palmsMesh.commitVertices()
            return
        }

        val leftTwistedForearm = leftSol.poseForearm.rotatedAroundY(leftSol.wristTwistRad)
        val rightTwistedForearm = rightSol.poseForearm.rotatedAroundY(rightSol.wristTwistRad)

        for (i in 0 until totalVerts) {
            val base = i * stride
            val px = src[base]; val py = src[base + 1]; val pz = src[base + 2]
            val nx = src[base + 3]; val ny = src[base + 4]; val nz = src[base + 5]
            val sol = if (sides[i] < 0f) leftSol else rightSol
            val twistedForearm = if (sides[i] < 0f) leftTwistedForearm else rightTwistedForearm
            val tHand = wHand[i]

            if (sol.poseProgress <= 0.0001f) {
                dst[base] = px; dst[base + 1] = py; dst[base + 2] = pz
                dst[base + 3] = nx; dst[base + 4] = ny; dst[base + 5] = nz
            } else if (tHand < 0.999f) {
                val (posW, normW) = BlossomArmIkRig.applyBlendedJointTransform(
                    px, py, pz, nx, ny, nz,
                    restPivot = sol.restHand.origin,
                    posePivot = sol.wrist,
                    restA = sol.restForearm,
                    poseA = twistedForearm,
                    restB = sol.restHand,
                    poseB = sol.poseHand,
                    t = tHand
                )
                val nLen = normW.len().coerceAtLeast(1e-5f)
                dst[base] = posW.x; dst[base + 1] = posW.y; dst[base + 2] = posW.z
                dst[base + 3] = normW.x / nLen; dst[base + 4] = normW.y / nLen; dst[base + 5] = normW.z / nLen
            } else {
                val lp = sol.restHand.worldToLocalPoint(px, py, pz)
                val wp = sol.poseHand.localToWorldPoint(lp.x, lp.y, lp.z)
                val ln = sol.restHand.worldToLocalDir(nx, ny, nz)
                val wn = sol.poseHand.localToWorldDir(ln.x, ln.y, ln.z)
                val nLen = wn.len().coerceAtLeast(1e-5f)
                dst[base] = wp.x; dst[base + 1] = wp.y; dst[base + 2] = wp.z
                dst[base + 3] = wn.x / nLen; dst[base + 4] = wn.y / nLen; dst[base + 5] = wn.z / nLen
            }
            dst[base + 6] = src[base + 6]
            dst[base + 7] = src[base + 7]
            dst[base + 8] = src[base + 8]
            dst[base + 9] = src[base + 9]
        }
        palmsMesh.commitVertices()
    }

    // -------------------------------------------------------------------------
    // 1B. 10 PROPORTIONATE ARTICULATED 3D FINGERS & THUMBS (OUTWARD CCW BOTH HANDS)
    // -------------------------------------------------------------------------
    fun buildBlossomHandsMesh(): DynamicGlMesh {
        val fingerSpecs = BLOSSOM_FINGER_SPECS
        val cols = FINGER_COLS
        val rows = FINGER_ROWS
        val vertsPerFinger = cols * rows
        val trisPerFinger = (cols - 1) * (rows - 1) * 6
        val totalFingers = fingerSpecs.size * 2

        val mesh = DynamicGlMesh(vertsPerFinger * totalFingers, trisPerFinger * totalFingers)
        val indices = ShortArray(trisPerFinger * totalFingers)

        var fingerIdx = 0
        var idxOut = 0
        val sides = floatArrayOf(-1f, +1f)
        for (side in sides) {
            for (specIdx in fingerSpecs.indices) {
                val baseVert = fingerIdx * vertsPerFinger
                for (r in 0 until rows - 1) {
                    for (c in 0 until cols - 1) {
                        val i0 = (baseVert + r * cols + c).toShort()
                        val i1 = (baseVert + r * cols + c + 1).toShort()
                        val i2 = (baseVert + (r + 1) * cols + c).toShort()
                        val i3 = (baseVert + (r + 1) * cols + c + 1).toShort()
                        if (side > 0f) {
                            indices[idxOut++] = i0; indices[idxOut++] = i1; indices[idxOut++] = i2
                            indices[idxOut++] = i1; indices[idxOut++] = i3; indices[idxOut++] = i2
                        } else {
                            indices[idxOut++] = i0; indices[idxOut++] = i2; indices[idxOut++] = i1
                            indices[idxOut++] = i1; indices[idxOut++] = i2; indices[idxOut++] = i3
                        }
                    }
                }
                fingerIdx++
            }
        }
        mesh.setIndices(indices)

        // Populate resting hand geometry via the shared articulated solver
        val leftRest = BlossomArmIkRig.solveSideArmIk(-1f, 0f, 0f)
        val rightRest = BlossomArmIkRig.solveSideArmIk(+1f, 0f, 0f)
        updateArticulatedHandsInternal(mesh, leftRest, rightRest)
        return mesh
    }

    /**
     * Real-time 60 FPS update of all 10 multi-joint articulated fingers and 10 matching glossy
     * 3D fingernails driven by Blossom's 2-bone arm IK solution.
     */
    fun updateArticulatedHandsAndFingernails(
        handsMesh: DynamicGlMesh,
        nailsMesh: DynamicGlMesh,
        leftSol: BlossomArmIkRig.SideArmIkSolution,
        rightSol: BlossomArmIkRig.SideArmIkSolution
    ) {
        updateArticulatedHandsInternal(handsMesh, leftSol, rightSol)
        updateArticulatedFingernailsInternal(nailsMesh, leftSol, rightSol)
    }

    private fun updateArticulatedHandsInternal(
        mesh: DynamicGlMesh,
        leftSol: BlossomArmIkRig.SideArmIkSolution,
        rightSol: BlossomArmIkRig.SideArmIkSolution
    ) {
        val floorZ = BlossomBodyBuilder.MATTRESS_TOP_Z + 0.015f
        val fingerSpecs = BLOSSOM_FINGER_SPECS
        val cols = FINGER_COLS
        val rows = FINGER_ROWS
        val vertsPerFinger = cols * rows
        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val sides = floatArrayOf(-1f, +1f)

        var fingerIdx = 0
        for (side in sides) {
            val sol = if (side < 0f) leftSol else rightSol
            val isResting = sol.poseProgress <= 0.0001f
            val floorClampW = 1f - smoothStep(0f, 0.10f, sol.poseProgress)

            for (specIdx in fingerSpecs.indices) {
                val spec = fingerSpecs[specIdx]
                val baseVert = fingerIdx * vertsPerFinger
                val flex = BlossomArmIkRig.fingerFlexForIndex(specIdx, sol.fingerCurlWeight)

                for (r in 0 until rows) {
                    val s = r.toFloat() / (rows - 1)
                    val (radScale, tipAngle) = fingerRadiusProfile(s, spec.isThumb)
                    val spine = evaluateArticulatedFingerSpine(spec, side, s, flex)
                    val radialW = cos(tipAngle)
                    val fwdW = sin(tipAngle)
                    val rootBlend = 1f - smoothStep(0f, 0.24f, s)

                    // Sculpted anatomical dorsal knuckle contours aligned to exact rigid hinge centers
                    val pipCrease = if (spec.isThumb) {
                        exp(-((s - 0.58f) / 0.038f).let { it * it })
                    } else {
                        exp(-((s - 0.48f) / 0.036f).let { it * it })
                    }
                    val dipCrease = if (spec.isThumb) {
                        0f
                    } else {
                        exp(-((s - 0.75f) / 0.032f).let { it * it })
                    }

                    for (c in 0 until cols) {
                        val u = c.toFloat() / (cols - 1)
                        val phi = u * (2f * PI.toFloat())
                        val cosPhi = cos(phi)
                        val sinPhi = sin(phi)

                        val nailMask = smoothBell(s, 0.78f, 0.95f, 0.04f) * smoothStep(0.45f, 0.92f, sinPhi) *
                            (1f - smoothStep(0.55f, 0.88f, abs(cosPhi)))
                        val nailIndent = -0.0028f * nailMask

                        val localLat = spec.rLat * radScale * cosPhi
                        var localDors = spec.rZ * radScale * sinPhi + nailIndent
                        if (sinPhi < 0f) {
                            // Softer fleshy pad on distal fingertip, slightly flatter palmar surface on proximal phalanx
                            val padPlump = 0.89f + 0.15f * smoothStep(0.72f, 0.94f, s)
                            localDors *= padPlump
                        } else {
                            // Distinct dorsal bony knuckle prominence at PIP/IP & DIP (even clearer when finger bends!)
                            val bendBoost = 1f + 0.55f * (abs(flex.pipFlexRad) / 1.4f).coerceIn(0f, 1f)
                            localDors += (0.0052f * pipCrease * bendBoost + 0.0032f * dipCrease * bendBoost) * sinPhi
                        }

                        var px = spine.cx + spine.latX * localLat + spine.dorsX * localDors
                        var py = spine.cy + spine.latY * localLat + spine.dorsY * localDors
                        var pz = spine.cz + spine.latZ * localLat + spine.dorsZ * localDors
                        if (floorClampW > 0.001f && pz < floorZ) {
                            pz = pz + (floorZ - pz) * floorClampW
                        }

                        var nx = spine.latX * (cosPhi * radialW / spec.rLat) +
                            spine.dorsX * (sinPhi * radialW / spec.rZ) +
                            spine.tanX * (fwdW / spec.rLat)
                        var ny = spine.latY * (cosPhi * radialW / spec.rLat) +
                            spine.dorsY * (sinPhi * radialW / spec.rZ) +
                            spine.tanY * (fwdW / spec.rLat)
                        var nz = spine.latZ * (cosPhi * radialW / spec.rLat) +
                            spine.dorsZ * (sinPhi * radialW / spec.rZ) +
                            spine.tanZ * (fwdW / spec.rLat)
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                        nx /= nLen; ny /= nLen; nz /= nLen

                        // Seamless normal fusion into unified SDF palm at knuckle root
                        if (rootBlend > 0.001f) {
                            val sdfNorm = BlossomSdfBodyBuilder.evaluateBodyNormal(px, py, pz, eps = 0.016f)
                            val mx = nx * (1f - rootBlend) + sdfNorm[0] * rootBlend
                            val my = ny * (1f - rootBlend) + sdfNorm[1] * rootBlend
                            val mz = nz * (1f - rootBlend) + sdfNorm[2] * rootBlend
                            val mLen = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-5f)
                            nx = mx / mLen; ny = my / mLen; nz = mz / mLen
                        }

                        // Map cleanly from rest hand frame into live posed 2-bone IK hand frame!
                        if (!isResting) {
                            val lp = sol.restHand.worldToLocalPoint(px, py, pz)
                            val wp = sol.poseHand.localToWorldPoint(lp.x, lp.y, lp.z)
                            val ln = sol.restHand.worldToLocalDir(nx, ny, nz)
                            val wn = sol.poseHand.localToWorldDir(ln.x, ln.y, ln.z)
                            val wnLen = wn.len().coerceAtLeast(1e-5f)
                            px = wp.x; py = wp.y; pz = wp.z
                            nx = wn.x / wnLen; ny = wn.y / wnLen; nz = wn.z / wnLen
                        }

                        val interdigitalAo = 1f - abs(cosPhi) * 0.20f * (1f - smoothStep(0.08f, 0.62f, s))
                        val padAo = 1f - smoothStep(0.10f, 0.85f, -sinPhi) * 0.26f
                        val knuckleAo = 1f - (0.06f * pipCrease + 0.05f * dipCrease) * smoothStep(0.25f, 0.90f, sinPhi)
                        val ao = (interdigitalAo * padAo * knuckleAo).coerceIn(0.42f, 1f)
                        val wetness = (0.055f + nailMask * 0.22f).coerceIn(0f, 0.45f)
                        val pigment = (0.05f + smoothStep(0.68f, 0.96f, s) * 0.07f +
                            (pipCrease * 0.05f + dipCrease * 0.04f) * smoothStep(0.2f, 0.9f, sinPhi) +
                            nailMask * 0.14f).coerceIn(0f, 1f)

                        val vIdx = (baseVert + r * cols + c) * stride
                        data[vIdx] = px; data[vIdx + 1] = py; data[vIdx + 2] = pz
                        data[vIdx + 3] = nx; data[vIdx + 4] = ny; data[vIdx + 5] = nz
                        data[vIdx + 6] = nailMask * 0.16f
                        data[vIdx + 7] = wetness
                        data[vIdx + 8] = ao
                        data[vIdx + 9] = pigment
                    }
                }
                fingerIdx++
            }
        }
        mesh.commitVertices()
    }

    private fun updateArticulatedFingernailsInternal(
        nailsMesh: DynamicGlMesh,
        leftSol: BlossomArmIkRig.SideArmIkSolution,
        rightSol: BlossomArmIkRig.SideArmIkSolution
    ) {
        val nailCols = NAIL_COLS
        val nailRows = NAIL_ROWS
        val vertsPerNail = nailCols * nailRows
        val fingerSpecs = BLOSSOM_FINGER_SPECS
        val data = nailsMesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val sides = floatArrayOf(-1f, +1f)

        var nailIdx = 0
        for (side in sides) {
            val sol = if (side < 0f) leftSol else rightSol
            val isResting = sol.poseProgress <= 0.0001f

            for (specIdx in fingerSpecs.indices) {
                val spec = fingerSpecs[specIdx]
                val baseVert = nailIdx * vertsPerNail
                val flex = BlossomArmIkRig.fingerFlexForIndex(specIdx, sol.fingerCurlWeight)

                for (r in 0 until nailRows) {
                    val v = r.toFloat() / (nailRows - 1)
                    // Natural short oval fingernails hugging flush onto the distal nailbed (ZERO pointy claw overhang!)
                    val s = if (spec.isThumb) 0.76f + v * 0.185f else 0.775f + v * 0.175f
                    val (radScale, tipAngle) = fingerRadiusProfile(s, spec.isThumb)
                    val spine = evaluateArticulatedFingerSpine(spec, side, s, flex)

                    val vCentered = v * 2f - 1f
                    val almondWidth = sqrt((1f - vCentered * vCentered * 0.34f).coerceAtLeast(0.32f))
                    val nailArcHalf = if (spec.isThumb) 0.36f * PI.toFloat() else 0.31f * PI.toFloat()
                    val radialW = cos(tipAngle * 0.60f)
                    val fwdW = sin(tipAngle * 0.60f)

                    for (c in 0 until nailCols) {
                        val u = c.toFloat() / (nailCols - 1)
                        val uCentered = u * 2f - 1f
                        val phi = (PI.toFloat() * 0.5f) - uCentered * nailArcHalf * almondWidth
                        val cosPhi = cos(phi)
                        val sinPhi = sin(phi)

                        var nx = spine.latX * (cosPhi * radialW / spec.rLat) +
                            spine.dorsX * (sinPhi * radialW / spec.rZ) +
                            spine.tanX * (fwdW / spec.rLat)
                        var ny = spine.latY * (cosPhi * radialW / spec.rLat) +
                            spine.dorsY * (sinPhi * radialW / spec.rZ) +
                            spine.tanY * (fwdW / spec.rLat)
                        var nz = spine.latZ * (cosPhi * radialW / spec.rLat) +
                            spine.dorsZ * (sinPhi * radialW / spec.rZ) +
                            spine.tanZ * (fwdW / spec.rLat)
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                        nx /= nLen; ny /= nLen; nz /= nLen

                        val edgeBevel = sin(v * PI.toFloat()).coerceAtLeast(0.15f) * cos(uCentered * PI.toFloat() * 0.46f)
                        val plateLift = 0.0044f * (0.45f + 0.55f * edgeBevel)
                        val localLat = spec.rLat * radScale * cosPhi
                        val localDors = spec.rZ * radScale * sinPhi

                        var px = spine.cx + spine.latX * localLat + spine.dorsX * localDors + nx * plateLift
                        var py = spine.cy + spine.latY * localLat + spine.dorsY * localDors + ny * plateLift
                        var pz = spine.cz + spine.latZ * localLat + spine.dorsZ * localDors + nz * plateLift

                        if (!isResting) {
                            val lp = sol.restHand.worldToLocalPoint(px, py, pz)
                            val wp = sol.poseHand.localToWorldPoint(lp.x, lp.y, lp.z)
                            val ln = sol.restHand.worldToLocalDir(nx, ny, nz)
                            val wn = sol.poseHand.localToWorldDir(ln.x, ln.y, ln.z)
                            val wnLen = wn.len().coerceAtLeast(1e-5f)
                            px = wp.x; py = wp.y; pz = wp.z
                            nx = wn.x / wnLen; ny = wn.y / wnLen; nz = wn.z / wnLen
                        }

                        val nailAo = (0.86f + 0.14f * cos(uCentered * PI.toFloat() * 0.45f) -
                            0.14f * (1f - smoothStep(0f, 0.20f, v))).coerceIn(0.62f, 1f)

                        val vIdx = (baseVert + r * nailCols + c) * stride
                        data[vIdx] = px; data[vIdx + 1] = py; data[vIdx + 2] = pz
                        data[vIdx + 3] = nx; data[vIdx + 4] = ny; data[vIdx + 5] = nz
                        data[vIdx + 6] = v
                        data[vIdx + 7] = u
                        data[vIdx + 8] = nailAo
                        data[vIdx + 9] = 0.0f // fingernail
                    }
                }
                nailIdx++
            }
        }
        nailsMesh.commitVertices()
    }

    // -------------------------------------------------------------------------
    // 1B. 20 SCULPTED 3D KERATIN FINGERNAILS (10) & TOENAILS (10) — OUTWARD CCW!
    //     Returns Pair(nailsMesh, toeNailStartVert) so fingernails track arms & toenails track legs!
    // -------------------------------------------------------------------------
    fun buildBlossomNailsMesh(): Pair<DynamicGlMesh, Int> {
        val nailCols = NAIL_COLS
        val nailRows = NAIL_ROWS
        val vertsPerNail = nailCols * nailRows
        val trisPerNail = (nailCols - 1) * (nailRows - 1) * 6

        val fingerSpecs = BLOSSOM_FINGER_SPECS
        val toeSpecs = BlossomSdfBodyBuilder.BLOSSOM_TOE_SPECS
        val totalNails = (fingerSpecs.size + toeSpecs.size) * 2
        val toeNailStartVert = fingerSpecs.size * 2 * vertsPerNail

        val mesh = DynamicGlMesh(vertsPerNail * totalNails, trisPerNail * totalNails)
        val indices = ShortArray(trisPerNail * totalNails)
        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val sides = floatArrayOf(-1f, +1f)

        var nailIdx = 0
        var idxOut = 0

        fun emitNailIndices(baseVert: Int, side: Float) {
            for (r in 0 until nailRows - 1) {
                for (c in 0 until nailCols - 1) {
                    val i0 = (baseVert + r * nailCols + c).toShort()
                    val i1 = (baseVert + r * nailCols + c + 1).toShort()
                    val i2 = (baseVert + (r + 1) * nailCols + c).toShort()
                    val i3 = (baseVert + (r + 1) * nailCols + c + 1).toShort()
                    // d/dc = +lat, d/dr = +fwd -> side=+1 needs fwd x lat (+Z); side=-1 needs lat x fwd (+Z)
                    if (side > 0f) {
                        indices[idxOut++] = i0; indices[idxOut++] = i2; indices[idxOut++] = i1
                        indices[idxOut++] = i1; indices[idxOut++] = i2; indices[idxOut++] = i3
                    } else {
                        indices[idxOut++] = i0; indices[idxOut++] = i1; indices[idxOut++] = i2
                        indices[idxOut++] = i1; indices[idxOut++] = i3; indices[idxOut++] = i2
                    }
                }
            }
        }

        // Part 1: Reserve index topology for 10 Curved Almond Fingernails (vertices filled via articulated solver below)
        for (side in sides) {
            for (specIdx in fingerSpecs.indices) {
                emitNailIndices(nailIdx * vertsPerNail, side)
                nailIdx++
            }
        }
        val leftRest = BlossomArmIkRig.solveSideArmIk(-1f, 0f, 0f)
        val rightRest = BlossomArmIkRig.solveSideArmIk(+1f, 0f, 0f)
        updateArticulatedFingernailsInternal(mesh, leftRest, rightRest)

        // Part 2: 10 Sculpted Natural Flush Toenails seated cleanly on upgraded distal toes
        for (side in sides) {
            for ((specIdx, spec) in toeSpecs.withIndex()) {
                val baseVert = nailIdx * vertsPerNail
                val isHallux = specIdx == 0
                emitNailIndices(baseVert, side)

                val fwdX = side * sin(spec.yawOut)
                val fwdY = -cos(spec.yawOut)
                val latX = side * cos(spec.yawOut)
                val latY = sin(spec.yawOut)

                for (r in 0 until nailRows) {
                    val v = r.toFloat() / (nailRows - 1)
                    // Natural short broad toenails sitting flush on distal toe dorsal bed
                    val s = if (isHallux) 0.61f + v * 0.29f else 0.64f + v * 0.25f

                    val tipT = smoothStep(0.84f, 1.0f, s)
                    val tipAngle = tipT * (PI.toFloat() * 0.5f)
                    val tipScale = cos(tipAngle).coerceAtLeast(0.03f)
                    val rootFlare = 1f + (if (isHallux) 0.16f else 0.12f) * (1f - smoothStep(0.0f, 0.22f, s))
                    val phalanxNeck = 1f - 0.085f * exp(-((s - 0.34f) / 0.15f).let { it * it })
                    val ipKnuckle = 1f + (if (isHallux) 0.055f else 0.072f) * exp(-((s - 0.50f) / 0.09f).let { it * it })
                    val distalPad = 1f + (if (isHallux) 0.085f else 0.115f) * exp(-((s - 0.76f) / 0.14f).let { it * it })
                    val radScale = rootFlare * phalanxNeck * ipKnuckle * distalPad * tipScale

                    val along = -0.095f + s * (spec.length + 0.095f)
                    val curlZ = spec.archLift * sin(s * PI.toFloat()) -
                        (if (isHallux) 0.018f else 0.030f) * smoothStep(0.42f, 0.94f, s)
                    val medialCurve = -0.022f * smoothStep(0.28f, 1.0f, s) * when (specIdx) {
                        0 -> 0.10f
                        1 -> 0.32f
                        2 -> 0.58f
                        3 -> 0.85f
                        else -> 1.15f
                    }

                    val cx = side * spec.rootAx + fwdX * along + latX * medialCurve
                    val cy = spec.rootY + fwdY * along + latY * medialCurve
                    val cz = spec.rootZ + curlZ

                    val vCentered = v * 2f - 1f
                    // Real toenails are gently squared-oval (especially hallux) rather than narrow points
                    val toePlateWidth = sqrt((1f - vCentered * vCentered * (if (isHallux) 0.16f else 0.24f)).coerceAtLeast(0.45f))
                    val toeArcHalf = if (isHallux) 0.36f * PI.toFloat() else 0.30f * PI.toFloat()

                    for (c in 0 until nailCols) {
                        val u = c.toFloat() / (nailCols - 1)
                        val uCentered = u * 2f - 1f
                        val phi = (PI.toFloat() * 0.5f) - uCentered * toeArcHalf * toePlateWidth
                        val cosP = cos(phi)
                        val sinP = sin(phi)

                        val radialW = cos(tipAngle * 0.50f)
                        val fwdW = sin(tipAngle * 0.50f)
                        var nx = latX * (cosP * radialW / spec.rLat) + fwdX * (fwdW / spec.rLat)
                        var ny = latY * (cosP * radialW / spec.rLat) + fwdY * (fwdW / spec.rLat)
                        var nz = sinP * radialW / spec.rZ
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                        nx /= nLen; ny /= nLen; nz /= nLen

                        val edgeBevel = sin(v * PI.toFloat()).coerceAtLeast(0.18f) * cos(uCentered * PI.toFloat() * 0.45f)
                        val plateLift = 0.0044f * (0.48f + 0.52f * edgeBevel)
                        val localLat = spec.rLat * radScale * cosP
                        val localZ = spec.rZ * radScale * sinP

                        val px = cx + latX * localLat + nx * plateLift
                        val py = cy + latY * localLat + ny * plateLift
                        val pz = cz + localZ + nz * plateLift

                        val nailAo = (0.85f + 0.15f * cos(uCentered * PI.toFloat() * 0.45f) -
                            0.14f * (1f - smoothStep(0f, 0.22f, v))).coerceIn(0.60f, 1f)

                        val vIdx = (baseVert + r * nailCols + c) * stride
                        data[vIdx] = px; data[vIdx + 1] = py; data[vIdx + 2] = pz
                        data[vIdx + 3] = nx; data[vIdx + 4] = ny; data[vIdx + 5] = nz
                        data[vIdx + 6] = v
                        data[vIdx + 7] = u
                        data[vIdx + 8] = nailAo
                        data[vIdx + 9] = 1.0f // toenail
                    }
                }
                nailIdx++
            }
        }

        mesh.setIndices(indices)
        mesh.commitVertices()
        return Pair(mesh, toeNailStartVert)
    }

    // -------------------------------------------------------------------------
    // 2. LIVE-DEFORMABLE SOFT ROUNDED MEDIUM BREASTS (EXACT OUTWARD CCW WINDING!)
    // -------------------------------------------------------------------------
    class BreastSideCache(val side: Float, val cols: Int, val rows: Int) {
        val count = cols * rows
        val baseX = FloatArray(count)
        val baseY = FloatArray(count)
        val baseZ = FloatArray(count)
        val baseNx = FloatArray(count)
        val baseNy = FloatArray(count)
        val baseNz = FloatArray(count)
        val baseAo = FloatArray(count)
        val basePigment = FloatArray(count)
        val localEx = FloatArray(count)
        val localEy = FloatArray(count)
        val edgeR = FloatArray(count)
    }

    class BlossomBreastsCache(
        val left: BreastSideCache,
        val right: BreastSideCache
    )

    fun buildBreastsCache(): BlossomBreastsCache {
        fun sampleSide(side: Float): BreastSideCache {
            val cache = BreastSideCache(side, BREAST_COLS, BREAST_ROWS)
            val cx = side * BREAST_CENTER_AX
            val cy = BREAST_CENTER_Y

            for (r in 0 until BREAST_ROWS) {
                val vRaw = (r.toFloat() / (BREAST_ROWS - 1)) * 2f - 1f
                // Tight radial center clustering (~1mm vertex spacing across areola & nipple papilla!)
                val v = 0.46f * vRaw + 0.54f * vRaw * vRaw * vRaw
                for (c in 0 until BREAST_COLS) {
                    val uRaw = (c.toFloat() / (BREAST_COLS - 1)) * 2f - 1f
                    val u = 0.46f * uRaw + 0.54f * uRaw * uRaw * uRaw

                    val ex = u * sqrt((1f - 0.5f * v * v).coerceAtLeast(0.0001f))
                    val ey = v * sqrt((1f - 0.5f * u * u).coerceAtLeast(0.0001f))
                    val edgeR = sqrt(ex * ex + ey * ey).coerceIn(0f, 1f)

                    // Asymmetric horizontal footprint: reaches exact sternum midline (x=0) medially without over-extending into arms laterally
                    val isLateral = ex * side > 0f
                    val rxHere = if (isLateral) BREAST_LATERAL_R else BREAST_MEDIAL_R
                    val x = cx + ex * rxHere
                    val y = cy + ey * BREAST_VERTICAL_R
                    val sample = BlossomSdfBodyBuilder.sampleBodySurfacePoint(x, y)

                    val idx = r * BREAST_COLS + c
                    cache.baseX[idx] = x
                    cache.baseY[idx] = y
                    cache.baseZ[idx] = sample.z
                    cache.baseNx[idx] = sample.nx
                    cache.baseNy[idx] = sample.ny
                    cache.baseNz[idx] = sample.nz
                    cache.baseAo[idx] = sample.ao
                    cache.basePigment[idx] = sample.pigment
                    cache.localEx[idx] = ex
                    cache.localEy[idx] = ey
                    cache.edgeR[idx] = edgeR
                }
            }
            return cache
        }
        return BlossomBreastsCache(left = sampleSide(-1f), right = sampleSide(+1f))
    }

    /**
     * Exact CCW outward winding (i0, i1, i2), (i1, i3, i2) so gl_FrontFacing == true across
     * the entire breast surface and lighting/skin color matches the torso 100% seamlessly!
     */
    fun buildBreastsIndices(): ShortArray {
        val cols = BREAST_COLS
        val rows = BREAST_ROWS
        val vertsPerSide = cols * rows
        val trisPerSide = (cols - 1) * (rows - 1) * 6
        val indices = ShortArray(trisPerSide * 2)
        var out = 0
        for (sideIdx in 0..1) {
            val base = sideIdx * vertsPerSide
            for (r in 0 until rows - 1) {
                for (c in 0 until cols - 1) {
                    val i0 = (base + r * cols + c).toShort()
                    val i1 = (base + r * cols + c + 1).toShort()
                    val i2 = (base + (r + 1) * cols + c).toShort()
                    val i3 = (base + (r + 1) * cols + c + 1).toShort()
                    indices[out++] = i0; indices[out++] = i1; indices[out++] = i2
                    indices[out++] = i1; indices[out++] = i3; indices[out++] = i2
                }
            }
        }
        return indices
    }

    /**
     * Real-time 60 FPS update of Blossom's soft anatomical breasts with true 3D (X, Y, AND Z) volume scaling:
     *  - Preserves the natural medium teardrop silhouette at default `breastSize = 0.50`
     *  - Expands width, upper/lower vertical fullness, roundness, and natural sternum cleavage at Large (`1.00`)
     *    instead of stretching into a pointy Z-only cone
     *  - Gently tucks lateral breast tissue medially when Blossom brings her arms forward in the spread pose
     *    so large breasts clear upper arms cleanly and naturally deepen cleavage!
     */
    fun updateBlossomBreastsMesh(
        mesh: DynamicGlMesh,
        cache: BlossomBreastsCache,
        state: VulvaDeformationState,
        computeNormals: (FloatArray, Int, Int, Boolean, Boolean) -> Unit
    ) {
        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val sideBuf = FloatArray(cache.left.count * stride)

        val sizeNorm = state.breastSize.coerceIn(0f, 1f)
        val largeT = smoothStep(0.45f, 1.0f, sizeNorm)
        val armSqueeze = state.selfSpreadPose.coerceIn(0f, 1f) * smoothStep(0.28f, 1.0f, sizeNorm)

        // Broad organic anatomical chest base at ALL sizes (Petite -> Medium -> Full grow smoothly out of chest, NEVER stuck-on!)
        val footprintScaleX = 0.86f + sizeNorm * 0.26f   // 0.86 (Petite) -> 0.99 (Medium) -> 1.12 (Full!)
        val footprintScaleY = 0.82f + sizeNorm * 0.26f   // 0.82 (Petite) -> 0.95 (Medium) -> 1.08 (Full!)
        val moundZScale = 0.74f + sizeNorm * 0.64f       // 0.74 (Petite) -> 1.06 (Medium) -> 1.38 (Full — lush & round!)
        val domePower = 1.32 - sizeNorm * 0.30           // 1.32 (Petite soft slope) -> 1.17 (Medium) -> 1.02 (Full plush!)

        fun updateSide(sideCache: BreastSideCache, shiftX: Float, shiftY: Float, sideOffsetVerts: Int) {
            val side = sideCache.side
            val sx = shiftX.coerceIn(-1f, 1f) * (0.20f + 0.09f * sizeNorm)
            val sy = shiftY.coerceIn(-1f, 1f) * (0.18f + 0.08f * sizeNorm)
            val medialGather = (-sx * side).coerceIn(-0.16f, 0.22f)
            val liftFullness = (sy * 0.18f).coerceIn(-0.04f, 0.05f)

            // Anatomically spaced supine apex (mid-clavicular line at Petite/Medium; gently gathered medially at Full!)
            val apexEx = side * (0.068f - 0.042f * largeT - 0.048f * armSqueeze)
            val apexEy = -(0.052f + 0.046f * sizeNorm)

            for (i in 0 until sideCache.count) {
                val ex = sideCache.localEx[i]
                val ey = sideCache.localEy[i]
                val edgeR = sideCache.edgeR[i]

                // Soft broad chest-wall collar blend (stays lush toward sternum midline so breasts sit organically on torso)
                val isMedialSide = smoothStep(0.08f, -0.52f, ex * side)
                val collarInner = 0.70f + 0.14f * largeT * isMedialSide
                val collarOuter = 0.965f + 0.020f * largeT * isMedialSide
                val collarFade = 1f - smoothStep(collarInner, collarOuter, edgeR)
                val softShiftW = (1f - smoothStep(0.0f, 0.93f, edgeR)).let { t -> t * t * (3f - 2f * t) }

                // True X & Y scaled teardrop dome (broad organic root even at Petite/Medium -> fuller sternum cleavage at Large!)
                val dxRel = ex - apexEx
                val dyRel = ey - apexEy
                val medialSpread = (0.90f + 0.28f * largeT) * footprintScaleX
                val lateralSpread = (0.88f - 0.05f * armSqueeze) * footprintScaleX
                val upperSpread = (0.98f + 0.08f * largeT) * footprintScaleY
                val lowerSpread = (0.80f + 0.16f * largeT) * footprintScaleY

                val dxA = dxRel / (if (dxRel * side > 0f) lateralSpread else medialSpread)
                val dyA = dyRel / (if (dyRel > 0f) upperSpread else lowerSpread)
                val rDome = sqrt(dxA * dxA + dyA * dyA).coerceIn(0f, 1f)
                val cosHem = cos(rDome * (PI.toFloat() * 0.5f)).coerceAtLeast(0f)
                val roundedDome = Math.pow(cosHem.toDouble(), domePower).toFloat() * collarFade

                // Smooth pectoral-to-breast upper slope & plush natural lower/medial fullness across ALL sizes
                val upperSlopeSoften = 1f - (0.26f - 0.14f * sizeNorm) * smoothStep(0.10f, 0.88f, ey)
                val lowerPlush = 1f + (0.14f + 0.18f * sizeNorm) * smoothStep(-0.04f, -0.66f, ey) * (1f - smoothStep(0.74f, 0.96f, edgeR))
                val medialPlush = 1f + (0.10f + 0.22f * largeT + 0.12f * armSqueeze) * smoothStep(-0.02f, -0.68f, ex * side) * (1f - smoothStep(0.76f, 0.97f, edgeR))
                val lateralDrape = 1f + (0.04f + 0.04f * sizeNorm - 0.06f * armSqueeze) * smoothStep(0.08f, 0.64f, ex * side) * (1f - smoothStep(0.68f, 0.94f, edgeR))

                val tailDx = (ex * side - 0.44f) / 0.34f
                val tailDy = (ey - 0.36f) / 0.32f
                val tailOfSpence = 0.036f * moundZScale * exp(-(tailDx * tailDx + tailDy * tailDy)) * collarFade
                val moundHeight = (0.455f * moundZScale + medialGather * 0.18f + liftFullness) *
                    roundedDome * upperSlopeSoften * lowerPlush * medialPlush * lateralDrape + tailOfSpence

                // High-resolution anatomical Areola (~4.0 cm organic slightly oval disc + Montgomery tubercles)
                val relWx = dxRel * (if (dxRel * side > 0f) BREAST_LATERAL_R else BREAST_MEDIAL_R)
                val relWy = dyRel * BREAST_VERTICAL_R
                val distApexWorld = sqrt(relWx * relWx + relWy * relWy)
                val ang = kotlin.math.atan2(relWy, relWx)
                val organicEdge = 1f + 0.044f * sin(ang * 3f + side * 1.1f) +
                    0.026f * cos(ang * 5f - side * 0.7f) +
                    0.018f * sin(ang * 7f + side * 2.1f)
                val distOrg = sqrt((relWx * 0.96f).let { it * it } + (relWy * 1.03f).let { it * it }) / organicEdge

                val areolaScale = 0.88f + 0.22f * sizeNorm
                val areolaMask = (1f - smoothStep(0.135f * areolaScale, 0.232f * areolaScale, distOrg)) * collarFade
                val areolaCushion = 0.011f * (1f - smoothStep(0.035f, 0.215f * areolaScale, distOrg)) * collarFade
                val montRing = smoothBell(distApexWorld, 0.092f * areolaScale, 0.182f * areolaScale, 0.028f)
                val montA = (sin(ang * 7f + side * 0.8f) * cos(ang * 3f - distApexWorld * 44f)).coerceAtLeast(0f)
                val montB = (cos(ang * 11f - side * 1.4f) * sin(distApexWorld * 58f + 0.6f)).coerceAtLeast(0f)
                val montBump = 0.0042f * montRing * (montA * montA * 0.65f + montB * montB * 0.35f) * collarFade

                val basalGroove = -0.0072f * exp(-((distApexWorld - 0.075f) / 0.017f).let { it * it }) * collarFade

                val nipR = 0.066f
                val nipT = (distApexWorld / nipR).coerceIn(0f, 1.4f)
                val nippleDome = if (nipT < 1f) {
                    val flatCrown = Math.pow((1.0 - Math.pow(nipT.toDouble(), 3.4)), 0.48).toFloat()
                    val tipOrganic = 1f + 0.032f * sin(ang * 4f + 0.5f) * smoothStep(0.12f, 0.65f, nipT) +
                        0.020f * cos(ang * 6f - 0.9f) * smoothStep(0.18f, 0.72f, nipT)
                    0.060f * flatCrown * tipOrganic * collarFade
                } else {
                    0f
                }
                val nippleMask = (1f - smoothStep(0.054f, 0.078f, distApexWorld)) * collarFade

                val collarBias = 0.0024f * smoothStep(0.74f, 0.88f, edgeR) * (1f - smoothStep(0.92f, 0.96f, edgeR)) -
                    0.0036f * smoothStep(0.95f, 1.0f, edgeR)
                val totalLift = moundHeight + areolaCushion + montBump + basalGroove + nippleDome + collarBias

                // Lateral-to-medial breast tissue gathering for close natural cleavage & clean upper-arm clearance in spread pose!
                val lateralTuckMask = smoothStep(-0.15f, 0.75f, ex * side) * roundedDome
                val armTuckX = -side * (0.140f * armSqueeze * lateralTuckMask + 0.050f * armSqueeze * roundedDome)
                val medGather = 0.042f * smoothStep(0.15f, 0.55f, sizeNorm) * (1f - largeT)
                val cleavageGatherX = -side * ((0.225f * largeT + medGather) * roundedDome * (0.50f + 0.50f * isMedialSide))
                val lowerGravityY = -0.090f * largeT * roundedDome * (0.45f + 0.55f * smoothStep(0.15f, -0.75f, ey))

                // Blend chest-wall outward normal with anterior +Z and subtle gravity drape
                val latDrapeNorm = (0.06f + 0.02f * sizeNorm - 0.065f * largeT - 0.055f * armSqueeze).coerceAtLeast(-0.02f)
                val nxB = sideCache.baseNx[i] * 0.30f + side * latDrapeNorm * softShiftW
                val nyB = sideCache.baseNy[i] * 0.22f - (0.045f + 0.040f * sizeNorm) * softShiftW
                val nzB = (sideCache.baseNz[i] * 0.56f + 0.44f).coerceAtLeast(0.50f)
                val dirLen = sqrt(nxB * nxB + nyB * nyB + nzB * nzB).coerceAtLeast(1e-5f)

                var px = sideCache.baseX[i] + (nxB / dirLen) * totalLift + sx * softShiftW + cleavageGatherX + armTuckX
                val py = sideCache.baseY[i] + (nyB / dirLen) * totalLift + sy * softShiftW + lowerGravityY
                var pz = sideCache.baseZ[i] + (nzB / dirLen) * totalLift +
                    (medialGather * 0.07f - abs(sx) * 0.04f) * softShiftW +
                    0.048f * armSqueeze * lateralTuckMask

                // Smooth C1 midline sternum soft-tissue contact: large breasts press softly together at x=0 creating
                // authentic cleavage with ZERO mesh self-intersection or Z-fighting!
                val signedPx = px * side
                val minMidGap = 0.004f
                var cleavageContact = 0f
                if (signedPx < 0.14f && roundedDome > 0.01f) {
                    val pressDepth = (0.14f - signedPx).coerceAtLeast(0f)
                    cleavageContact = smoothStep(0.14f, 0.008f, signedPx) * roundedDome
                    if (signedPx < 0.06f) {
                        val delta = signedPx - minMidGap
                        // Smooth softplus clamp keeps signedPx strictly >= 0.004f with C1 continuity
                        val clamped = minMidGap + 0.5f * (delta + sqrt(delta * delta + 0.0012f))
                        px = side * clamped
                    }
                    // Real soft tissue bulges gently anteriorly right along the cleavage contact ridge!
                    pz += 0.036f * cleavageContact * (0.5f + 0.5f * largeT) + pressDepth * 0.08f * roundedDome
                }

                // Natural curved inframammary crease AO + warm natural sternum cleavage shadow + basal nipple shadow ring
                val infraCurveY = (-0.70f - 0.14f * largeT) + 0.14f * ex * ex
                val infraMask = exp(-(((ey - infraCurveY) / 0.17f).let { it * it } + (ex / 0.72f).let { it * it })) * collarFade
                val cleavageSoft = exp(-(((ex * side + 0.68f) / 0.28f).let { it * it } + ((ey + 0.04f) / 0.52f).let { it * it })) * collarFade
                val cleavageCreaseAo = cleavageContact * (0.18f + 0.16f * largeT) * smoothStep(0.10f, 0.006f, abs(px))
                val nippleRimAo = exp(-((distApexWorld - 0.074f) / 0.018f).let { it * it }) * 0.20f * collarFade
                val areolaRimSoft = (0.045f * montRing * (1f - montA * 0.7f)) * collarFade
                val ao = (sideCache.baseAo[i] * (1f - (0.14f + 0.14f * sizeNorm) * infraMask -
                    (0.05f + 0.08f * largeT) * cleavageSoft - cleavageCreaseAo - nippleRimAo - areolaRimSoft)).coerceIn(0.22f, 1f)

                // Realistic velvety-matte warm dusty-rose / cocoa-rose areola & deeper papilla (ZERO plastic gloss!)
                val mucosa = (areolaMask * 0.44f + nippleMask * 0.22f).coerceIn(0f, 0.68f)
                val wetness = (0.040f - areolaMask * 0.008f + nippleMask * 0.020f).coerceIn(0.024f, 0.075f)
                val pigment = (sideCache.basePigment[i] + areolaMask * 0.56f + nippleMask * 0.28f).coerceIn(0f, 0.96f)

                val idx = i * stride
                sideBuf[idx] = px; sideBuf[idx + 1] = py; sideBuf[idx + 2] = pz
                sideBuf[idx + 6] = mucosa
                sideBuf[idx + 7] = wetness
                sideBuf[idx + 8] = ao
                sideBuf[idx + 9] = pigment
            }

            computeNormals(sideBuf, sideCache.cols, sideCache.rows, false, false)

            // Exact outer-collar normal fusion into unified SDF ribcage -> ZERO seam!
            for (i in 0 until sideCache.count) {
                val edgeR = sideCache.edgeR[i]
                val nBlend = smoothStep(0.72f, 0.95f, edgeR)
                val idx = i * stride
                if (nBlend > 0.001f) {
                    val mx = sideBuf[idx + 3] * (1f - nBlend) + sideCache.baseNx[i] * nBlend
                    val my = sideBuf[idx + 4] * (1f - nBlend) + sideCache.baseNy[i] * nBlend
                    val mz = sideBuf[idx + 5] * (1f - nBlend) + sideCache.baseNz[i] * nBlend
                    val len = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-5f)
                    sideBuf[idx + 3] = mx / len
                    sideBuf[idx + 4] = my / len
                    sideBuf[idx + 5] = mz / len
                }
            }

            System.arraycopy(sideBuf, 0, data, sideOffsetVerts * stride, sideBuf.size)
        }

        updateSide(cache.left, state.leftBreastX, state.leftBreastY, 0)
        updateSide(cache.right, state.rightBreastX, state.rightBreastY, cache.left.count)
        mesh.commitVertices()
    }

    // -------------------------------------------------------------------------
    // 3. SCULPTURAL REALISTIC FEMININE HEAD & FACE (120x108, OUTWARD CCW!)
    //    theta runs BOTTOM-TO-TOP (PI -> 0) so d/dc=+X, d/dr=+Y => +Z OUTWARD!
    // -------------------------------------------------------------------------
    fun buildBlossomHeadMesh(
        buildIndices: (Int, Int) -> ShortArray,
        computeNormals: (FloatArray, Int, Int, Boolean, Boolean) -> Unit
    ): DynamicGlMesh {
        val cols = 120
        val rows = 108
        val mesh = DynamicGlMesh(cols * rows, (cols - 1) * (rows - 1) * 6)
        mesh.setIndices(buildIndices(cols, rows))

        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX

        for (r in 0 until rows) {
            // Run v from 0 (neck/chin bottom, theta = PI) UP to 1 (crown top, theta = 0) -> strictly +Y!
            val vRaw = 1f - (r.toFloat() / (rows - 1))
            val vCentered = vRaw * 2f - 1f
            val vCluster = 0.66f * vCentered + 0.34f * vCentered * vCentered * vCentered
            val theta = ((vCluster + 1f) * 0.5f).coerceIn(0.004f, 0.996f) * PI.toFloat()
            val sinT = sin(theta)
            val cosT = cos(theta)

            for (c in 0 until cols) {
                // Cluster horizontal resolution tightly across anterior face (nose, eyes & lips ~4mm vertex spacing!)
                val uRaw = (c.toFloat() / (cols - 1)) * 2f - 1f
                val uCluster = 0.52f * uRaw + 0.48f * uRaw * uRaw * uRaw
                val phi = uCluster * PI.toFloat()
                val sinP = sin(phi)
                val cosP = cos(phi)

                // Classical adult feminine oval head (Rule-of-Thirds vertical balance & sleek jaw tapering to delicate chin!)
                val yScale = 0.885f + 0.070f * smoothStep(-0.35f, 0.35f, cosT)
                val rawY = cosT * yScale
                val frontFace = smoothStep(-0.12f, 0.48f, cosP)

                val lowerFaceT = smoothStep(0.06f, -0.76f, rawY)
                val jawAngle = 1f + 0.014f * exp(-((rawY + 0.42f) / 0.14f).let { it * it }) * smoothStep(0.45f, 0.86f, abs(sinP))
                val jawTaper = 1f - 0.400f * lowerFaceT * lowerFaceT * (0.48f + 0.52f * frontFace)
                val chinRound = 1f - 0.145f * smoothStep(-0.50f, -0.82f, rawY) * frontFace
                val templeHollow = 1f - 0.058f * exp(-((rawY - 0.22f) / 0.24f).let { it * it }) * smoothStep(0.36f, 0.86f, abs(sinP))
                val cheekPlaneSlim = 1f - 0.046f * exp(-((rawY + 0.24f) / 0.22f).let { it * it }) * smoothStep(0.38f, 0.84f, abs(sinP)) * frontFace
                val craniumBackFull = 1f + 0.07f * exp(-((rawY - 0.16f) / 0.48f).let { it * it }) * smoothStep(0.10f, 0.80f, -cosP)

                var lx = sinT * sinP * 0.642f * jawAngle * jawTaper * chinRound * templeHollow * cheekPlaneSlim
                var ly = rawY
                var lz = sinT * cosP * 0.745f * craniumBackFull

                // Refined anterior facial planes: smooth forehead, balanced mid-face, delicate rounded chin
                if (cosP > 0f) {
                    val foreheadPlane = -0.018f * smoothStep(0.28f, 0.72f, ly) * cosP * cosP
                    val lowerSlope = -0.042f * smoothStep(-0.04f, -0.58f, ly) * cosP * cosP
                    val chinForwardSupport = 0.042f * exp(-((rawY + 0.695f) / 0.125f).let { it * it }) * cosP * cosP
                    lz += foreheadPlane + lowerSlope + chinForwardSupport
                }

                // Graceful curved submental underside flowing organically into the slender cervical neck (ZERO flat chin shelf!)
                val submentalT = smoothStep(0.840f * PI.toFloat(), 0.985f * PI.toFloat(), theta)
                if (submentalT > 0.001f) {
                    val targetNeckX = sinP * 0.370f
                    val targetNeckY = -0.84f - 0.16f * smoothStep(0.89f * PI.toFloat(), 1.0f * PI.toFloat(), theta)
                    val targetNeckZ = -0.22f + cosP * 0.370f
                    lx = lx * (1f - submentalT) + targetNeckX * submentalT
                    ly = ly * (1f - submentalT) + targetNeckY * submentalT
                    lz = lz * (1f - submentalT) + targetNeckZ * submentalT
                }

                val ax = abs(lx)
                var dzFace = 0f
                var faceMucosa = 0f
                var faceWetness = 0.030f
                var faceAo = 1f
                var facePigment = 0.008f

                if (cosP > 0.14f && submentalT < 0.45f) {
                    val fW = smoothStep(0.14f, 0.54f, cosP) * (1f - submentalT)

                    // 1. Classical Feminine Facial Planes (sculpted cheekbones beneath outer eyes, soft jawline, delicate chin)
                    val zygomaCheek = 0.030f * exp(-(((ax - 0.370f) / 0.155f).let { it * it } + ((ly + 0.068f) / 0.135f).let { it * it }))
                    val buccalSoftHollow = -0.012f * exp(-(((ax - 0.330f) / 0.130f).let { it * it } + ((ly + 0.340f) / 0.140f).let { it * it }))
                    val chinForm = 0.028f * exp(-((ax / 0.145f).let { it * it } + ((ly + 0.690f) / 0.098f).let { it * it }))
                    val jawRimDef = 0.012f * exp(-(((ax - 0.270f) / 0.135f).let { it * it } + ((ly + 0.595f) / 0.088f).let { it * it }))
                    val browArchY = 0.172f + 0.034f * exp(-((ax - 0.332f) / 0.105f).let { it * it }) - 0.040f * smoothStep(0.345f, 0.455f, ax)
                    val browBone = 0.018f * exp(-(((ax - 0.258f) / 0.205f).let { it * it } + ((ly - browArchY) / 0.070f).let { it * it }))
                    val glabellaBridge = 0.010f * exp(-((ax / 0.095f).let { it * it } + ((ly - 0.145f) / 0.072f).let { it * it }))
                    val tearTrough = -0.007f * exp(-(((ax - 0.248f) / 0.120f).let { it * it } + ((ly + 0.056f) / 0.040f).let { it * it }))
                    val nasolabialSoft = 0.006f * exp(-(((ax - 0.215f) / 0.080f).let { it * it } + ((ly + 0.325f) / 0.115f).let { it * it }))
                    dzFace += (zygomaCheek + buccalSoftHollow + chinForm + jawRimDef + browBone + glabellaBridge + tearTrough + nasolabialSoft) * fW

                    // 2. Classical Rule-of-5-Eyes Proportions (eye width = faceWidth/5, 1 full eye-width between inner corners!)
                    val exEye = (ax - 0.256f) / 0.142f
                    val eyEye = (ly - 0.036f) / 0.088f
                    val orbitR2 = exEye * exEye * 0.54f + eyEye * eyEye * 0.48f
                    val orbitHollow = -0.068f * exp(-orbitR2 * 1.48f)

                    val eyeU = ((ax - 0.256f) / 0.126f).coerceIn(-1.25f, 1.25f)
                    val eyeSpan = (1f - eyeU * eyeU).coerceAtLeast(0f)
                    val upperLidY = 0.036f + (0.056f + 0.008f * eyeU) * sqrt(eyeSpan)
                    val lowerLidY = 0.036f - (0.036f - 0.006f * eyeU) * sqrt(eyeSpan)
                    val insideAperture = if (abs(eyeU) < 0.985f) {
                        smoothStep(lowerLidY - 0.0050f, lowerLidY + 0.0050f, ly) *
                            (1f - smoothStep(upperLidY - 0.0050f, upperLidY + 0.0050f, ly))
                    } else 0f

                    val apertureRecess = -0.120f * insideAperture
                    val upperLidFold = 0.032f * exp(-((ly - (upperLidY + 0.018f)) / 0.018f).let { it * it }) * smoothStep(1.06f, 0.62f, abs(eyeU))
                    val upperCrease = -0.017f * exp(-((ly - (upperLidY + 0.042f)) / 0.014f).let { it * it }) * smoothStep(1.02f, 0.52f, abs(eyeU))
                    val lowerLidMargin = 0.018f * exp(-((ly - (lowerLidY - 0.014f)) / 0.015f).let { it * it }) * smoothStep(1.04f, 0.60f, abs(eyeU))
                    dzFace += (orbitHollow + apertureRecess + upperLidFold + upperCrease + lowerLidMargin) * fW

                    // Natural tapered eyebrows spanning inner-to-outer eye (strictly below lash pigment threshold -> ZERO dark dots!)
                    val lashThick = 0.0092f + 0.0032f * smoothStep(-0.25f, 0.88f, eyeU)
                    val lashLine = exp(-((ly - (upperLidY + 0.003f)) / lashThick).let { it * it }) * smoothStep(1.04f, 0.66f, abs(eyeU))
                    val lowerLash = exp(-((ly - (lowerLidY - 0.002f)) / 0.0065f).let { it * it }) * smoothStep(0.92f, 0.42f, abs(eyeU)) * 0.24f
                    val browThickness = 0.0205f * (1f - 0.42f * smoothStep(0.310f, 0.450f, ax))
                    val browMask = exp(-((ly - browArchY) / browThickness).let { it * it }) *
                        smoothStep(0.098f, 0.148f, ax) * (1f - smoothStep(0.388f, 0.456f, ax))

                    // 3. Classical Balanced Feminine Nose (alar wings aligned directly beneath inner eye corners @ ax=0.13!)
                    val nasionDip = -0.010f * exp(-((ax / 0.068f).let { it * it } + ((ly - 0.068f) / 0.046f).let { it * it }))
                    val bridgeW = 0.058f + 0.016f * smoothStep(0.068f, -0.21f, ly)
                    val bridgeSpan = smoothBell(ly, -0.228f, 0.075f, 0.062f)
                    val bridgeProfile = 0.026f + 0.046f * smoothStep(0.068f, -0.21f, ly)
                    val supratipBreak = -0.003f * exp(-((ax / 0.068f).let { it * it } + ((ly + 0.175f) / 0.038f).let { it * it }))
                    val noseBridge = bridgeProfile * exp(-((ax / bridgeW).let { it * it })) * bridgeSpan
                    val noseTip = 0.056f * exp(-((ax / 0.082f).let { it * it } + ((ly + 0.216f) / 0.064f).let { it * it }))
                    val noseAla = 0.035f * exp(-(((ax - 0.090f) / 0.048f).let { it * it } + ((ly + 0.244f) / 0.042f).let { it * it }))
                    val alarCrease = -0.008f * exp(-(((ax - 0.132f) / 0.024f).let { it * it } + ((ly + 0.246f) / 0.046f).let { it * it }))
                    val nostrilCut = -0.014f * exp(-(((ax - 0.054f) / 0.028f).let { it * it } + ((ly + 0.268f) / 0.024f).let { it * it }))
                    val philtrumDimple = -0.008f * exp(-((ax / 0.032f).let { it * it })) * smoothBell(ly, -0.390f, -0.290f, 0.030f)
                    val philtralCols = 0.005f * exp(-(((ax - 0.044f) / 0.020f).let { it * it })) * smoothBell(ly, -0.394f, -0.296f, 0.026f)
                    dzFace += (nasionDip + noseBridge + supratipBreak + noseTip + noseAla + alarCrease + nostrilCut + philtrumDimple + philtralCols) * fW

                    // 4. Classical Proportioned Natural Feminine Lips (width aligned below medial pupils, balanced nose-to-chin ratio!)
                    val lipU = (ax / 0.222f).coerceIn(0f, 1.35f)
                    val lipSpan = smoothStep(1.04f, 0.48f, lipU)
                    val gentleCornerCurve = 0.015f * lipU * lipU
                    val cupidDip = -0.009f * exp(-((ax / 0.032f).let { it * it })) +
                        0.009f * exp(-(((ax - 0.054f) / 0.034f).let { it * it }))
                    val lipPartY = -0.440f + gentleCornerCurve
                    val upperLipY = -0.408f + cupidDip + gentleCornerCurve * 0.30f
                    val lowerLipY = -0.470f + gentleCornerCurve * 0.55f

                    val upperLipVol = 0.024f * exp(-((ly - upperLipY) / 0.024f).let { it * it }) * lipSpan
                    val upperTubercle = 0.005f * exp(-((ax / 0.042f).let { it * it } + ((ly + 0.418f) / 0.018f).let { it * it }))
                    val lowerLipVol = 0.031f * exp(-((ly - lowerLipY) / 0.029f).let { it * it }) * smoothStep(0.95f, 0.36f, lipU)
                    val lipPartGroove = -0.012f * exp(-((ly - lipPartY) / 0.0095f).let { it * it }) * smoothStep(0.98f, 0.48f, lipU)
                    val cornerDimple = -0.009f * exp(-(((ax - 0.228f) / 0.026f).let { it * it } + ((ly + 0.428f) / 0.024f).let { it * it }))
                    val mentolabialDip = -0.014f * exp(-((ax / 0.145f).let { it * it } + ((ly + 0.578f) / 0.050f).let { it * it }))
                    dzFace += (upperLipVol + upperTubercle + lowerLipVol + lipPartGroove + cornerDimple + mentolabialDip) * fW

                    // Natural muted lip rose + clean skin-matched cheeks (zero orange/reddish cheek darkening!)
                    val lipColorMask = ((exp(-((ly - upperLipY) / 0.026f).let { it * it }) +
                        exp(-((ly - lowerLipY) / 0.031f).let { it * it })) * lipSpan).coerceIn(0f, 1f)
                    val cheekWarmth = 0.010f * exp(-(((ax - 0.365f) / 0.175f).let { it * it } + ((ly + 0.08f) / 0.150f).let { it * it }))

                    faceMucosa = (lipColorMask * 0.44f + cheekWarmth).coerceIn(0f, 0.48f)
                    faceWetness = (0.030f + lipColorMask * 0.038f).coerceIn(0.026f, 0.072f)
                    val nostrilAo = exp(-(((ax - 0.054f) / 0.032f).let { it * it } + ((ly + 0.266f) / 0.026f).let { it * it })) * 0.22f
                    val lipLineAo = exp(-((ly - lipPartY) / 0.0100f).let { it * it }) * lipSpan * 0.24f
                    val creaseAo = exp(-((ly - (upperLidY + 0.042f)) / 0.015f).let { it * it }) * smoothStep(1.00f, 0.52f, abs(eyeU)) * 0.20f
                    val orbitShadowAo = exp(-orbitR2 * 1.90f) * 0.06f
                    val browDarkAo = browMask * 0.28f
                    val lashDarkAo = (lashLine * 0.62f + lowerLash * 0.24f).coerceIn(0f, 0.66f)
                    faceAo = (1f - nostrilAo - lipLineAo - creaseAo - orbitShadowAo - browDarkAo - lashDarkAo).coerceIn(0.24f, 1f)
                    // Clean separation: brows stay in 0.08..0.54 (hair-matched) while lashes reach 0.72..0.92 (dark lashes)!
                    val browPigment = (browMask * 0.50f).coerceAtMost(0.52f)
                    val lashPigment = lashLine * 0.88f + lowerLash * 0.36f
                    facePigment = (0.008f + max(browPigment, lashPigment) + lipColorMask * 0.06f).coerceIn(0f, 0.96f)
                }

                // 5. Delicate Sculpted Ears on lateral temples
                val earRegion = smoothStep(0.72f, 0.96f, abs(sinP)) * smoothBell(ly, -0.24f, 0.12f, 0.08f) * (1f - submentalT)
                if (earRegion > 0.001f) {
                    val earLat = 0.056f * earRegion * exp(-((cosP + 0.16f) / 0.28f).let { it * it })
                    lx += (if (sinP >= 0f) 1f else -1f) * earLat
                    lz -= 0.018f * earRegion
                    faceAo *= (1f - 0.16f * earRegion * exp(-((cosP + 0.10f) / 0.18f).let { it * it }))
                }

                // Soft natural ambient occlusion gently transitioning under the jaw onto the neck
                if (submentalT > 0.001f) {
                    faceAo *= (1f - 0.14f * submentalT)
                }

                lz += dzFace

                val worldPt = headLocalToWorld(lx, ly, lz)
                val idx = (r * cols + c) * stride
                data[idx] = worldPt[0]
                data[idx + 1] = worldPt[1]
                data[idx + 2] = worldPt[2]
                data[idx + 6] = faceMucosa
                data[idx + 7] = faceWetness
                data[idx + 8] = faceAo
                data[idx + 9] = facePigment
            }
        }

        // Bottom-to-top v parameterization means tx x bx = (+X) x (+Y) = +Z OUTWARD!
        computeNormals(data, cols, rows, false, true)

        // Blend ONLY the tucked hidden neck-socket collar (theta > 0.92*PI) into SDF neck normals so jawline stays crisp & smooth!
        for (r in 0 until rows) {
            val vRaw = 1f - (r.toFloat() / (rows - 1))
            val vCentered = vRaw * 2f - 1f
            val vCluster = 0.66f * vCentered + 0.34f * vCentered * vCentered * vCentered
            val theta = ((vCluster + 1f) * 0.5f).coerceIn(0.004f, 0.996f) * PI.toFloat()
            val nBlend = smoothStep(0.92f * PI.toFloat(), 0.99f * PI.toFloat(), theta)
            if (nBlend > 0.001f) {
                for (c in 0 until cols) {
                    val idx = (r * cols + c) * stride
                    val sdfNorm = BlossomSdfBodyBuilder.evaluateBodyNormal(data[idx], data[idx + 1], data[idx + 2], eps = 0.018f)
                    val mx = data[idx + 3] * (1f - nBlend) + sdfNorm[0] * nBlend
                    val my = data[idx + 4] * (1f - nBlend) + sdfNorm[1] * nBlend
                    val mz = data[idx + 5] * (1f - nBlend) + sdfNorm[2] * nBlend
                    val len = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-5f)
                    data[idx + 3] = mx / len
                    data[idx + 4] = my / len
                    data[idx + 5] = mz / len
                }
            }
        }

        mesh.commitVertices()
        return mesh
    }

    // -------------------------------------------------------------------------
    // 4. NATURAL DEEPER-SET SAPPHIRE-BLUE 3D EYES (SEATED CALMLY BEHIND EYELIDS)
    // -------------------------------------------------------------------------
    fun buildBlossomEyesMesh(): DynamicGlMesh {
        val cols = 28
        val rows = 20
        val vertsPerEye = cols * rows
        val trisPerEye = (cols - 1) * (rows - 1) * 6

        val mesh = DynamicGlMesh(vertsPerEye * 2, trisPerEye * 2)
        val indices = ShortArray(trisPerEye * 2)
        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX

        val eyeRadius = 0.136f
        val eyeCenterY = 0.038f
        val eyeCenterZ = 0.520f
        val sides = floatArrayOf(-1f, +1f)

        var outIdx = 0
        for ((eyeIndex, side) in sides.withIndex()) {
            val baseVert = eyeIndex * vertsPerEye
            for (r in 0 until rows - 1) {
                for (c in 0 until cols - 1) {
                    val i0 = (baseVert + r * cols + c).toShort()
                    val i1 = (baseVert + r * cols + c + 1).toShort()
                    val i2 = (baseVert + (r + 1) * cols + c).toShort()
                    val i3 = (baseVert + (r + 1) * cols + c + 1).toShort()
                    // Verified strictly CCW outward: d/dr(+X) x d/dc(+Y) = +Z
                    indices[outIdx++] = i0; indices[outIdx++] = i2; indices[outIdx++] = i1
                    indices[outIdx++] = i1; indices[outIdx++] = i2; indices[outIdx++] = i3
                }
            }

            val cx = side * 0.256f
            for (r in 0 until rows) {
                val v = r.toFloat() / (rows - 1)
                val alpha = v * (0.66f * PI.toFloat())
                val sinA = sin(alpha)
                val cosA = cos(alpha)

                for (c in 0 until cols) {
                    val u = c.toFloat() / (cols - 1)
                    val phi = u * (2f * PI.toFloat())
                    val cosP = cos(phi)
                    val sinP = sin(phi)

                    val lnx = sinA * cosP
                    val lny = sinA * sinP
                    val lnz = cosA

                    val lx = cx + lnx * eyeRadius
                    val ly = eyeCenterY + lny * eyeRadius
                    val lz = eyeCenterZ + lnz * eyeRadius

                    val wPt = headLocalToWorld(lx, ly, lz)
                    val wN = headNormalToWorld(lnx, lny, lnz)

                    val irisR = sinA
                    // Soft natural upper eyelid cast shadow across upper sclera & top of iris
                    val upperLidShadow = (1f - smoothStep(-0.08f, 0.44f, lny) * 0.46f).coerceIn(0.42f, 1f)

                    val idx = (baseVert + r * cols + c) * stride
                    data[idx] = wPt[0]; data[idx + 1] = wPt[1]; data[idx + 2] = wPt[2]
                    data[idx + 3] = wN[0]; data[idx + 4] = wN[1]; data[idx + 5] = wN[2]
                    data[idx + 6] = irisR
                    data[idx + 7] = phi / (2f * PI.toFloat())
                    data[idx + 8] = upperLidShadow
                    data[idx + 9] = lny * 0.5f + 0.5f
                }
            }
        }

        mesh.setIndices(indices)
        mesh.commitVertices()
        return mesh
    }

    // -------------------------------------------------------------------------
    // 5. SOFT CHIN-TO-COLLARBONE LAYERED DARK-BROWN / SOFT-BLACK BOB (CAP + 34 LOCKS)
    // -------------------------------------------------------------------------
    fun buildBlossomHairMesh(): DynamicGlMesh {
        val capCols = 60
        val capRows = 42
        val capVerts = capCols * capRows
        val capIndices = (capCols - 1) * (capRows - 1) * 6

        data class LockSpec(
            val p0: FloatArray,
            val p1: FloatArray,
            val p2: FloatArray,
            val halfW: Float,
            val thick: Float,
            val shadeBias: Float
        )

        val locks = listOf(
            // 4 Sleek close-hugging curtain-bang ribbons framing forehead & temples smoothly without obscuring brows
            LockSpec(floatArrayOf(0.24f, 0.85f, 0.38f), floatArrayOf(-0.14f, 0.68f, 0.63f), floatArrayOf(-0.58f, 0.24f, 0.44f), 0.185f, 0.025f, 0.18f),
            LockSpec(floatArrayOf(0.18f, 0.87f, 0.40f), floatArrayOf(-0.28f, 0.60f, 0.58f), floatArrayOf(-0.67f, 0.06f, 0.32f), 0.170f, 0.024f, 0.14f),
            LockSpec(floatArrayOf(0.28f, 0.84f, 0.37f), floatArrayOf(0.54f, 0.54f, 0.47f), floatArrayOf(0.68f, 0.06f, 0.31f), 0.165f, 0.024f, 0.16f),
            LockSpec(floatArrayOf(0.12f, 0.92f, 0.32f), floatArrayOf(-0.34f, 0.72f, 0.52f), floatArrayOf(-0.70f, 0.20f, 0.30f), 0.170f, 0.026f, 0.22f),

            // 10 Longer face-framing layered bob side locks hugging cheekbones & jaw cleanly (left & right)
            LockSpec(floatArrayOf(-0.34f, 0.78f, 0.32f), floatArrayOf(-0.72f, 0.16f, 0.30f), floatArrayOf(-0.68f, -0.80f, 0.14f), 0.140f, 0.038f, 0.16f),
            LockSpec(floatArrayOf(-0.42f, 0.72f, 0.16f), floatArrayOf(-0.78f, 0.02f, 0.14f), floatArrayOf(-0.76f, -1.00f, -0.02f), 0.150f, 0.040f, 0.12f),
            LockSpec(floatArrayOf(-0.38f, 0.76f, 0.24f), floatArrayOf(-0.74f, 0.06f, 0.22f), floatArrayOf(-0.65f, -0.88f, 0.08f), 0.128f, 0.035f, 0.22f),
            LockSpec(floatArrayOf(-0.26f, 0.86f, 0.12f), floatArrayOf(-0.76f, 0.24f, 0.16f), floatArrayOf(-0.80f, -0.76f, -0.10f), 0.145f, 0.040f, 0.18f),
            LockSpec(floatArrayOf(-0.46f, 0.60f, 0.02f), floatArrayOf(-0.80f, -0.14f, -0.04f), floatArrayOf(-0.82f, -1.12f, -0.22f), 0.155f, 0.042f, 0.10f),

            LockSpec(floatArrayOf(0.32f, 0.78f, 0.34f), floatArrayOf(0.72f, 0.18f, 0.32f), floatArrayOf(0.68f, -0.80f, 0.14f), 0.140f, 0.038f, 0.16f),
            LockSpec(floatArrayOf(0.40f, 0.72f, 0.18f), floatArrayOf(0.78f, 0.02f, 0.14f), floatArrayOf(0.76f, -1.00f, -0.02f), 0.150f, 0.040f, 0.12f),
            LockSpec(floatArrayOf(0.28f, 0.76f, 0.40f), floatArrayOf(0.66f, 0.24f, 0.41f), floatArrayOf(0.65f, -0.74f, 0.20f), 0.128f, 0.035f, 0.22f),
            LockSpec(floatArrayOf(0.26f, 0.88f, 0.16f), floatArrayOf(0.76f, 0.24f, 0.18f), floatArrayOf(0.80f, -0.76f, -0.08f), 0.145f, 0.040f, 0.18f),
            LockSpec(floatArrayOf(0.46f, 0.60f, 0.02f), floatArrayOf(0.80f, -0.14f, -0.04f), floatArrayOf(0.82f, -1.12f, -0.22f), 0.155f, 0.042f, 0.10f),

            // 10 Lush pillow-draping & shoulder-grazing lower bob locks fanning softly beside her neck & upper shoulders
            LockSpec(floatArrayOf(-0.56f, 0.26f, -0.18f), floatArrayOf(-0.90f, -0.46f, -0.26f), floatArrayOf(-1.02f, -1.36f, -0.34f), 0.175f, 0.046f, 0.14f),
            LockSpec(floatArrayOf(-0.48f, 0.14f, -0.34f), floatArrayOf(-0.84f, -0.62f, -0.42f), floatArrayOf(-1.08f, -1.52f, -0.46f), 0.185f, 0.046f, 0.08f),
            LockSpec(floatArrayOf(-0.36f, 0.02f, -0.48f), floatArrayOf(-0.72f, -0.74f, -0.54f), floatArrayOf(-0.96f, -1.62f, -0.54f), 0.180f, 0.044f, 0.12f),
            LockSpec(floatArrayOf(-0.54f, 0.42f, -0.08f), floatArrayOf(-0.88f, -0.26f, -0.18f), floatArrayOf(-0.98f, -1.24f, -0.28f), 0.165f, 0.044f, 0.20f),
            LockSpec(floatArrayOf(-0.62f, 0.04f, 0.04f), floatArrayOf(-0.80f, -0.58f, 0.04f), floatArrayOf(-0.84f, -1.32f, -0.12f), 0.145f, 0.040f, 0.18f),

            LockSpec(floatArrayOf(0.56f, 0.26f, -0.18f), floatArrayOf(0.90f, -0.46f, -0.26f), floatArrayOf(1.02f, -1.36f, -0.34f), 0.175f, 0.046f, 0.14f),
            LockSpec(floatArrayOf(0.48f, 0.14f, -0.34f), floatArrayOf(0.84f, -0.62f, -0.42f), floatArrayOf(1.08f, -1.52f, -0.46f), 0.185f, 0.046f, 0.08f),
            LockSpec(floatArrayOf(0.36f, 0.02f, -0.48f), floatArrayOf(0.72f, -0.74f, -0.54f), floatArrayOf(0.96f, -1.62f, -0.54f), 0.180f, 0.044f, 0.12f),
            LockSpec(floatArrayOf(0.54f, 0.42f, -0.08f), floatArrayOf(0.88f, -0.26f, -0.18f), floatArrayOf(0.98f, -1.24f, -0.28f), 0.165f, 0.044f, 0.20f),
            LockSpec(floatArrayOf(0.62f, 0.04f, 0.04f), floatArrayOf(0.80f, -0.58f, 0.04f), floatArrayOf(0.84f, -1.32f, -0.12f), 0.145f, 0.040f, 0.18f),

            // 6 Layered dimensional crown volume locks (gives natural glossy silhouette from every angle)
            LockSpec(floatArrayOf(0.14f, 1.01f, 0.08f), floatArrayOf(-0.22f, 0.92f, 0.40f), floatArrayOf(-0.58f, 0.54f, 0.44f), 0.165f, 0.050f, 0.24f),
            LockSpec(floatArrayOf(0.24f, 0.99f, 0.02f), floatArrayOf(0.56f, 0.78f, 0.24f), floatArrayOf(0.78f, 0.30f, 0.20f), 0.160f, 0.048f, 0.18f),
            LockSpec(floatArrayOf(-0.08f, 1.01f, -0.08f), floatArrayOf(-0.52f, 0.76f, 0.16f), floatArrayOf(-0.78f, 0.20f, 0.10f), 0.160f, 0.048f, 0.14f),
            LockSpec(floatArrayOf(0.06f, 1.04f, -0.14f), floatArrayOf(-0.36f, 0.90f, -0.04f), floatArrayOf(-0.72f, 0.40f, -0.12f), 0.165f, 0.048f, 0.20f),
            LockSpec(floatArrayOf(0.12f, 1.02f, -0.16f), floatArrayOf(0.48f, 0.84f, -0.04f), floatArrayOf(0.76f, 0.34f, -0.12f), 0.160f, 0.046f, 0.16f),
            LockSpec(floatArrayOf(-0.02f, 1.04f, 0.14f), floatArrayOf(0.28f, 0.94f, 0.38f), floatArrayOf(0.62f, 0.56f, 0.42f), 0.155f, 0.046f, 0.22f)
        )

        val lockCols = 9
        val lockRows = 15
        val vertsPerLock = lockCols * lockRows
        val trisPerLock = (lockCols - 1) * (lockRows - 1) * 6

        val totalVerts = capVerts + locks.size * vertsPerLock
        val totalIndices = capIndices + locks.size * trisPerLock
        val mesh = DynamicGlMesh(totalVerts, totalIndices)
        val indices = ShortArray(totalIndices)
        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX

        var idxOut = 0
        for (r in 0 until capRows - 1) {
            for (c in 0 until capCols - 1) {
                val i0 = (r * capCols + c).toShort()
                val i1 = (r * capCols + c + 1).toShort()
                val i2 = ((r + 1) * capCols + c).toShort()
                val i3 = ((r + 1) * capCols + c + 1).toShort()
                indices[idxOut++] = i0; indices[idxOut++] = i2; indices[idxOut++] = i1
                indices[idxOut++] = i1; indices[idxOut++] = i2; indices[idxOut++] = i3
            }
        }

        // Part A: Layered Bob Crown & Pillow-Draping Nape Volume Cap
        for (r in 0 until capRows) {
            val v = r.toFloat() / (capRows - 1)
            for (c in 0 until capCols) {
                val u = (c.toFloat() / (capCols - 1)) * 2f - 1f
                val phi = u * PI.toFloat()
                val sinP = sin(phi)
                val cosP = cos(phi)

                val frontMask = smoothStep(-0.15f, 0.65f, cosP)
                val backMask = smoothStep(0.10f, 0.80f, -cosP)
                val sideMask = smoothStep(0.32f, 0.88f, abs(sinP))
                // Soft asymmetrical side-part curve across forehead so front bangs look feathered instead of straight-across blunt
                val partLift = 0.075f * smoothStep(-0.18f, 0.58f, sinP) * frontMask
                val maxTheta = 0.62f * PI.toFloat() -
                    (0.285f + partLift) * PI.toFloat() * frontMask +
                    0.24f * PI.toFloat() * backMask +
                    0.16f * PI.toFloat() * sideMask * (1f - frontMask)

                val theta = (0.008f + v * (maxTheta / PI.toFloat())).coerceAtMost(0.86f) * PI.toFloat()
                val sinT = sin(theta)
                val cosT = cos(theta)

                val crownVolume = 0.120f * (1f - smoothStep(0.28f, 0.92f, v)) + 0.048f * smoothStep(0.20f, 0.85f, v)
                val edgeTaper = 1f - 0.72f * smoothStep(0.80f, 1.0f, v)
                // Fine silky subtle hair-cap contour (eliminates chunky clay ridges along the forehead hairline!)
                val strandRib = 0.0060f * sin(phi * 20f + v * 4.5f) + 0.0035f * cos(phi * 36f - v * 6.2f)
                val loft = (crownVolume * edgeTaper + strandRib * (1f - smoothStep(0.84f, 1.0f, v))).coerceAtLeast(0.012f)

                val craniumBack = 1f + 0.10f * backMask * sinT
                // Gentle lateral fan-out at lower nape so longer back hair rests softly onto the headrest pillow
                val pillowFan = 0.18f * smoothStep(0.58f, 0.96f, v) * (backMask * 0.7f + sideMask * 0.5f) * (1f - frontMask)
                val lx = sinT * sinP * (0.69f + loft + pillowFan)
                val ly = cosT * (0.98f + loft * 0.92f) - 0.24f * smoothStep(0.65f, 1.0f, v) * (backMask + sideMask * 0.6f) * (1f - frontMask)
                val lz = sinT * cosP * (0.785f * craniumBack + loft)

                val wPt = headLocalToWorld(lx, ly, lz)
                val lnx = sinT * sinP + 0.055f * cos(phi * 20f + v * 4.5f) * cosP
                val lny = cosT
                val lnz = sinT * cosP - 0.055f * cos(phi * 20f + v * 4.5f) * sinP
                val wN = headNormalToWorld(lnx, lny, lnz)

                val strandShade = 0.5f + 0.5f * sin(phi * 26f + v * 5.0f)
                val ao = (0.78f + 0.22f * (1f - smoothStep(0.62f, 1.0f, v)) * (0.78f + 0.22f * strandShade)).coerceIn(0.45f, 1f)

                val idx = (r * capCols + c) * stride
                data[idx] = wPt[0]; data[idx + 1] = wPt[1]; data[idx + 2] = wPt[2]
                data[idx + 3] = wN[0]; data[idx + 4] = wN[1]; data[idx + 5] = wN[2]
                data[idx + 6] = v
                data[idx + 7] = strandShade
                data[idx + 8] = ao
                data[idx + 9] = 0.08f
            }
        }

        // Part B: 24 Layered Curved Pixie Hair Locks (verified outward CCW winding!)
        for ((lockIdx, lock) in locks.withIndex()) {
            val baseVert = capVerts + lockIdx * vertsPerLock
            for (r in 0 until lockRows - 1) {
                for (c in 0 until lockCols - 1) {
                    val i0 = (baseVert + r * lockCols + c).toShort()
                    val i1 = (baseVert + r * lockCols + c + 1).toShort()
                    val i2 = (baseVert + (r + 1) * lockCols + c).toShort()
                    val i3 = (baseVert + (r + 1) * lockCols + c + 1).toShort()
                    // B = T x O => d/dc(B) x d/dr(T) = B x T = +O (OUTWARD CCW!)
                    indices[idxOut++] = i0; indices[idxOut++] = i1; indices[idxOut++] = i2
                    indices[idxOut++] = i1; indices[idxOut++] = i3; indices[idxOut++] = i2
                }
            }

            for (r in 0 until lockRows) {
                val t = r.toFloat() / (lockRows - 1)
                val omt = 1f - t
                val cx = omt * omt * lock.p0[0] + 2f * omt * t * lock.p1[0] + t * t * lock.p2[0]
                val cy = omt * omt * lock.p0[1] + 2f * omt * t * lock.p1[1] + t * t * lock.p2[1]
                val cz = omt * omt * lock.p0[2] + 2f * omt * t * lock.p1[2] + t * t * lock.p2[2]

                var tx = 2f * omt * (lock.p1[0] - lock.p0[0]) + 2f * t * (lock.p2[0] - lock.p1[0])
                var ty = 2f * omt * (lock.p1[1] - lock.p0[1]) + 2f * t * (lock.p2[1] - lock.p1[1])
                var tz = 2f * omt * (lock.p1[2] - lock.p0[2]) + 2f * t * (lock.p2[2] - lock.p1[2])
                val tLen = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-5f)
                tx /= tLen; ty /= tLen; tz /= tLen

                val drapeW = smoothStep(-0.15f, -0.85f, cy)
                var ox = cx * (1f - 0.45f * drapeW)
                var oy = (cy - 0.12f) * (1f - drapeW) + 0.06f * drapeW
                var oz = cz * (1f - drapeW) + 0.92f * drapeW
                val oDotT = ox * tx + oy * ty + oz * tz
                ox -= tx * oDotT; oy -= ty * oDotT; oz -= tz * oDotT
                val oLen = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(1e-5f)
                ox /= oLen; oy /= oLen; oz /= oLen

                val bx = ty * oz - tz * oy
                val by = tz * ox - tx * oz
                val bz = tx * oy - ty * ox

                // Sleek silky overlapping layered bob ribbons (half-thickness eliminates chunky clay-slab edges!)
                val widthEnv = (0.74f + 0.36f * sin(t * PI.toFloat())).coerceIn(0.28f, 1.12f) *
                    (1f - 0.60f * smoothStep(0.76f, 1.0f, t))
                val curW = lock.halfW * 1.10f * widthEnv
                val curThick = lock.thick * 0.48f * (0.48f + 0.52f * sin(t * PI.toFloat())) * (1f - 0.55f * smoothStep(0.76f, 1.0f, t))

                for (c in 0 until lockCols) {
                    val u = (c.toFloat() / (lockCols - 1)) * 2f - 1f
                    val arch = cos(u * (PI.toFloat() * 0.5f))
                    val lx = cx + bx * (u * curW) + ox * (arch * curThick)
                    val ly = cy + by * (u * curW) + oy * (arch * curThick)
                    val lz = cz + bz * (u * curW) + oz * (arch * curThick)

                    val lnx = ox * (0.85f * arch + 0.15f) + bx * (u * 0.52f)
                    val lny = oy * (0.85f * arch + 0.15f) + by * (u * 0.52f)
                    val lnz = oz * (0.85f * arch + 0.15f) + bz * (u * 0.52f)

                    val wPt = headLocalToWorld(lx, ly, lz)
                    val minHairZ = BlossomBodyBuilder.mattressSurfaceZ(wPt[1]) + 0.03f
                    if (wPt[2] < minHairZ) wPt[2] = minHairZ
                    val wN = headNormalToWorld(lnx, lny, lnz)

                    val idx = (baseVert + r * lockCols + c) * stride
                    data[idx] = wPt[0]; data[idx + 1] = wPt[1]; data[idx + 2] = wPt[2]
                    data[idx + 3] = wN[0]; data[idx + 4] = wN[1]; data[idx + 5] = wN[2]
                    data[idx + 6] = t
                    data[idx + 7] = 0.5f + 0.5f * cos(u * PI.toFloat())
                    data[idx + 8] = (0.72f + 0.28f * arch).coerceIn(0.55f, 1f)
                    data[idx + 9] = lock.shadeBias
                }
            }
        }

        mesh.setIndices(indices)
        mesh.commitVertices()
        return mesh
    }

    // -------------------------------------------------------------------------
    // 6. PERSISTENT SIDE-TO-SIDE HEAD TURN RIG (YAW ±26 DEG + SUBTLE PILLOW ROLL)
    //    Disjoint from ArmSkinCache (ax >= 1.12) & LegSkinCache (y <= 0.44)!
    // -------------------------------------------------------------------------
    class HeadRigCache(
        val headRestPosNorm: FloatArray,
        val eyesRestPosNorm: FloatArray,
        val hairRestPosNorm: FloatArray,
        val neckBodyIndices: IntArray,
        val neckRestPosNorm: FloatArray,
        val neckWeights: FloatArray
    )

    fun buildHeadRigCache(
        bodyMesh: DynamicGlMesh,
        headMesh: DynamicGlMesh,
        eyesMesh: DynamicGlMesh,
        hairMesh: DynamicGlMesh
    ): HeadRigCache {
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        fun copyPosNorm(mesh: DynamicGlMesh): FloatArray {
            val src = mesh.vertexData
            val out = FloatArray(mesh.vertexCount * 6)
            for (i in 0 until mesh.vertexCount) {
                val s = i * stride
                val d = i * 6
                out[d] = src[s]; out[d + 1] = src[s + 1]; out[d + 2] = src[s + 2]
                out[d + 3] = src[s + 3]; out[d + 4] = src[s + 4]; out[d + 5] = src[s + 5]
            }
            return out
        }

        // Capture upper cervical neck vertices in blossomBodyMesh (strictly ax < 0.58, y >= 4.92 -> 100% disjoint from arms & legs)
        val bData = bodyMesh.vertexData
        val nVerts = bodyMesh.vertexCount
        val idxBuf = IntArray(nVerts)
        val wBuf = FloatArray(nVerts)
        var count = 0
        for (i in 0 until nVerts) {
            val base = i * stride
            val ax = abs(bData[base])
            val y = bData[base + 1]
            if (y in 4.92f..6.05f && ax < 0.58f) {
                val w = smoothStep(4.92f, 5.72f, y) * (1f - smoothStep(0.38f, 0.58f, ax))
                if (w > 0.002f) {
                    idxBuf[count] = i
                    wBuf[count] = w
                    count++
                }
            }
        }
        val neckIndices = idxBuf.copyOf(count)
        val neckWeights = wBuf.copyOf(count)
        val neckRest = FloatArray(count * 6)
        for (k in 0 until count) {
            val s = neckIndices[k] * stride
            val d = k * 6
            neckRest[d] = bData[s]; neckRest[d + 1] = bData[s + 1]; neckRest[d + 2] = bData[s + 2]
            neckRest[d + 3] = bData[s + 3]; neckRest[d + 4] = bData[s + 4]; neckRest[d + 5] = bData[s + 5]
        }

        return HeadRigCache(
            headRestPosNorm = copyPosNorm(headMesh),
            eyesRestPosNorm = copyPosNorm(eyesMesh),
            hairRestPosNorm = copyPosNorm(hairMesh),
            neckBodyIndices = neckIndices,
            neckRestPosNorm = neckRest,
            neckWeights = neckWeights
        )
    }

    /**
     * Smoothly rotates Blossom's head, eyes, layered hair, and upper neck side-to-side
     * (`headTurn` in `-1f..+1f` -> ±26 deg yaw + gentle ±3.9 deg pillow roll).
     */
    fun applyHeadTurn(
        bodyMesh: DynamicGlMesh,
        headMesh: DynamicGlMesh,
        eyesMesh: DynamicGlMesh,
        hairMesh: DynamicGlMesh,
        cache: HeadRigCache,
        headTurn: Float
    ) {
        val turn = headTurn.coerceIn(-1f, 1f)
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val pivotY = HEAD_CENTER_Y - 0.18f
        val pivotZ = HEAD_CENTER_Z - 0.22f

        fun restoreMesh(mesh: DynamicGlMesh, rest: FloatArray) {
            val dst = mesh.vertexData
            for (i in 0 until mesh.vertexCount) {
                val s = i * 6
                val d = i * stride
                dst[d] = rest[s]; dst[d + 1] = rest[s + 1]; dst[d + 2] = rest[s + 2]
                dst[d + 3] = rest[s + 3]; dst[d + 4] = rest[s + 4]; dst[d + 5] = rest[s + 5]
            }
            mesh.commitVertices()
        }

        if (abs(turn) < 1e-4f) {
            restoreMesh(headMesh, cache.headRestPosNorm)
            restoreMesh(eyesMesh, cache.eyesRestPosNorm)
            restoreMesh(hairMesh, cache.hairRestPosNorm)
            val bData = bodyMesh.vertexData
            for (k in cache.neckBodyIndices.indices) {
                val s = k * 6
                val d = cache.neckBodyIndices[k] * stride
                bData[d] = cache.neckRestPosNorm[s]
                bData[d + 1] = cache.neckRestPosNorm[s + 1]
                bData[d + 2] = cache.neckRestPosNorm[s + 2]
                bData[d + 3] = cache.neckRestPosNorm[s + 3]
                bData[d + 4] = cache.neckRestPosNorm[s + 4]
                bData[d + 5] = cache.neckRestPosNorm[s + 5]
            }
            bodyMesh.commitVertices()
            return
        }

        val fullYaw = turn * 0.488f // +/- 28 deg natural comfortable cervical rotation
        val fullRoll = -turn * 0.068f // +/- 3.9 deg gentle headrest pillow roll

        fun rotatePointAndNormal(
            px: Float, py: Float, pz: Float,
            nx: Float, ny: Float, nz: Float,
            yaw: Float, roll: Float,
            out: FloatArray
        ) {
            val cy = cos(yaw); val sy = sin(yaw)
            val cr = cos(roll); val sr = sin(roll)
            val rx = px
            val ry = py - pivotY
            val rz = pz - pivotZ

            // Yaw around +Y (+yaw turns face toward +X), then subtle roll around +Z
            val x1 = rx * cy + rz * sy
            val y1 = ry
            val z1 = -rx * sy + rz * cy

            val x2 = x1 * cr - y1 * sr
            val y2 = x1 * sr + y1 * cr
            val z2 = z1

            val nx1 = nx * cy + nz * sy
            val ny1 = ny
            val nz1 = -nx * sy + nz * cy

            var nx2 = nx1 * cr - ny1 * sr
            var ny2 = nx1 * sr + ny1 * cr
            var nz2 = nz1
            val nLen = sqrt(nx2 * nx2 + ny2 * ny2 + nz2 * nz2).coerceAtLeast(1e-5f)
            nx2 /= nLen; ny2 /= nLen; nz2 /= nLen

            out[0] = x2
            out[1] = pivotY + y2
            out[2] = pivotZ + z2
            out[3] = nx2
            out[4] = ny2
            out[5] = nz2
        }

        val tmp = FloatArray(6)

        fun applyRigid(mesh: DynamicGlMesh, rest: FloatArray) {
            val dst = mesh.vertexData
            for (i in 0 until mesh.vertexCount) {
                val s = i * 6
                val d = i * stride
                rotatePointAndNormal(
                    rest[s], rest[s + 1], rest[s + 2],
                    rest[s + 3], rest[s + 4], rest[s + 5],
                    fullYaw, fullRoll, tmp
                )
                dst[d] = tmp[0]; dst[d + 1] = tmp[1]; dst[d + 2] = tmp[2]
                dst[d + 3] = tmp[3]; dst[d + 4] = tmp[4]; dst[d + 5] = tmp[5]
            }
            mesh.commitVertices()
        }

        applyRigid(headMesh, cache.headRestPosNorm)
        applyRigid(eyesMesh, cache.eyesRestPosNorm)

        // Hair follows full head turn on crown/face-framing locks and softens gently on lower pillow-resting tips
        val hDst = hairMesh.vertexData
        val hRest = cache.hairRestPosNorm
        for (i in 0 until hairMesh.vertexCount) {
            val s = i * 6
            val d = i * stride
            val restY = hRest[s + 1]
            val hairW = 0.52f + 0.48f * smoothStep(5.05f, 5.85f, restY)
            rotatePointAndNormal(
                hRest[s], restY, hRest[s + 2],
                hRest[s + 3], hRest[s + 4], hRest[s + 5],
                fullYaw * hairW, fullRoll * hairW, tmp
            )
            val minHairZ = BlossomBodyBuilder.mattressSurfaceZ(tmp[1]) + 0.03f
            hDst[d] = tmp[0]
            hDst[d + 1] = tmp[1]
            hDst[d + 2] = if (tmp[2] < minHairZ) minHairZ else tmp[2]
            hDst[d + 3] = tmp[3]
            hDst[d + 4] = tmp[4]
            hDst[d + 5] = tmp[5]
        }
        hairMesh.commitVertices()

        // Smooth cervical twist across upper neck vertices in blossomBodyMesh
        val bData = bodyMesh.vertexData
        val nRest = cache.neckRestPosNorm
        for (k in cache.neckBodyIndices.indices) {
            val s = k * 6
            val d = cache.neckBodyIndices[k] * stride
            val w = cache.neckWeights[k]
            rotatePointAndNormal(
                nRest[s], nRest[s + 1], nRest[s + 2],
                nRest[s + 3], nRest[s + 4], nRest[s + 5],
                fullYaw * w * 0.72f, fullRoll * w * 0.65f, tmp
            )
            bData[d] = tmp[0]; bData[d + 1] = tmp[1]; bData[d + 2] = tmp[2]
            bData[d + 3] = tmp[3]; bData[d + 4] = tmp[4]; bData[d + 5] = tmp[5]
        }
        bodyMesh.commitVertices()
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun smoothBell(x: Float, low: Float, high: Float, feather: Float): Float {
        return smoothStep(low - feather, low + feather, x) * (1f - smoothStep(high - feather, high + feather, x))
    }
}
