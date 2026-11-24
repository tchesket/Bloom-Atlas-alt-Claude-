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
 * Unified Smooth-Min Signed Distance Field (SDF) + Surface Nets 3D lower-body generator for Blossom.
 *
 * Architectural purpose:
 *  - Fuses the reclining torso (lower ribcage, hourglass waist, rounded abdomen, navel, hips),
 *    dual-hemisphere buttocks, two-bone bent legs (thighs, sculpted knees, calves, slender ankles),
 *    and flat-resting sculpted feet into ONE unbroken watertight manifold using cubic polynomial
 *    smooth-min blending (`smin`).
 *  - Eliminates all separate-mesh Z-buffer intersection seams across the hips, inguinal folds,
 *    gluteal-to-posterior-thigh junction, and ankles.
 *  - Pairs with an ultra-high-resolution (128x144, ~0.5mm center spacing) deformable anterior
 *    vulva/perineum/anus patch projected directly onto the exact SDF=0 surface with matched
 *    analytical SDF gradient normals for a completely invisible skin transition.
 */
internal object BlossomSdfBodyBuilder {

    const val VULVA_PATCH_COLS = 128
    const val VULVA_PATCH_ROWS = 144

    // Elliptical patch footprint on the anterior pelvis (covers mons +0.72 through lower gluteal-cleft anus -0.86 at 100% detail!)
    const val PATCH_CENTER_Y = -0.06f
    const val PATCH_RX = 0.64f
    const val PATCH_RY = 1.02f

    private const val MIN_X = -2.96f
    private const val MAX_X = 2.96f
    private const val MIN_Y = -5.92f
    private const val MAX_Y = 6.32f
    private const val MIN_Z = -2.45f
    private const val MAX_Z = 2.80f
    private const val STEP = 0.082f

    private const val LUT_SIZE = 65
    private const val LUT_MIN_X = -0.72f
    private const val LUT_MAX_X = 0.72f
    private const val LUT_MIN_Y = -1.22f
    private const val LUT_MAX_Y = 1.08f

    class AnteriorSurfaceLut(
        val size: Int,
        val zValues: FloatArray
    ) {
        fun sampleZ(x: Float, y: Float): Float {
            val u = ((x - LUT_MIN_X) / (LUT_MAX_X - LUT_MIN_X)).coerceIn(0f, 1f) * (size - 1)
            val v = ((y - LUT_MIN_Y) / (LUT_MAX_Y - LUT_MIN_Y)).coerceIn(0f, 1f) * (size - 1)
            val ix0 = u.toInt().coerceIn(0, size - 2)
            val iy0 = v.toInt().coerceIn(0, size - 2)
            val fx = u - ix0
            val fy = v - iy0
            val r0 = iy0 * size + ix0
            val r1 = (iy0 + 1) * size + ix0
            val z0 = zValues[r0] + (zValues[r0 + 1] - zValues[r0]) * fx
            val z1 = zValues[r1] + (zValues[r1 + 1] - zValues[r1]) * fx
            return z0 + (z1 - z0) * fy
        }
    }

    fun buildAnteriorSurfaceLut(): AnteriorSurfaceLut {
        val table = FloatArray(LUT_SIZE * LUT_SIZE)
        for (j in 0 until LUT_SIZE) {
            val y = LUT_MIN_Y + (j.toFloat() / (LUT_SIZE - 1)) * (LUT_MAX_Y - LUT_MIN_Y)
            for (i in 0 until LUT_SIZE) {
                val x = LUT_MIN_X + (i.toFloat() / (LUT_SIZE - 1)) * (LUT_MAX_X - LUT_MIN_X)
                table[j * LUT_SIZE + i] = sampleBodySurfacePoint(x, y).z
            }
        }
        return AnteriorSurfaceLut(LUT_SIZE, table)
    }

    class BlossomVulvaPatchCache(
        val cols: Int,
        val rows: Int
    ) {
        val count = cols * rows
        val baseX = FloatArray(count)
        val baseY = FloatArray(count)
        val baseZ = FloatArray(count)
        val baseNx = FloatArray(count)
        val baseNy = FloatArray(count)
        val baseNz = FloatArray(count)
        val baseAo = FloatArray(count)
        val basePigment = FloatArray(count)
        val edgeR = FloatArray(count)
    }

    data class SdfSurfaceSample(
        val z: Float,
        val nx: Float,
        val ny: Float,
        val nz: Float,
        val ao: Float,
        val pigment: Float
    )

    fun mattressSurfaceZ(y: Float): Float = BlossomBodyBuilder.mattressSurfaceZ(y)

    /**
     * Evaluates the exact signed distance (negative inside, positive outside) to Blossom's
     * single unified reclining full body (feet, legs, buttocks, pelvis, abdomen, ribcage,
     * feminine shoulders, reclining arms & palms, and slender neck).
     */
    fun evaluateBodySdf(x: Float, y: Float, z: Float): Float {
        val ax = abs(x)

        // ---------------------------------------------------------------------
        // 1. CONTINUOUS RECLINING TORSO (PELVIS -> ABDOMEN -> WAIST -> RIBCAGE -> SHOULDERS)
        // ---------------------------------------------------------------------
        val yc = y.coerceIn(-1.04f, 4.98f)
        val matY = mattressSurfaceZ(yc)
        val upperFill = smoothStep(0.62f, 2.95f, yc)
        // Smooth organic posterior roll across the perineum into the gluteal cleft (zero protruding pouch!)
        val perinealRollZ = -0.34f * smoothStep(-0.34f, -1.04f, yc)
        // Upper back & shoulder blades settle naturally back onto the reclining exam table (never raised above head!)
        val thoraxSettle = -0.34f * smoothStep(2.55f, 4.86f, yc)
        val zSpine = (matY + 0.56f) + 0.28f * upperFill + perinealRollZ + thoraxSettle

        val hipFlare = 0.36f * exp(-((yc - 0.16f) / 1.12f).let { it * it })
        val iliacFull = 0.14f * exp(-((yc - 1.08f) / 0.68f).let { it * it })
        val waistPinch = -0.22f * exp(-((yc - 2.48f) / 0.62f).let { it * it })
        val ribCage = 0.05f * exp(-((yc - 3.92f) / 0.78f).let { it * it })
        val thoraxSlim = -0.14f * smoothStep(2.55f, 3.65f, yc)
        val shoulderSpan = 0.04f * exp(-((yc - 4.46f) / 0.44f).let { it * it })
        // Keep upper chest smoothly filled right to the axilla (~4.52) so shoulders flow naturally with ZERO pauldron notch!
        val shoulderTopTaper = 1f - 0.72f * smoothStep(4.50f, 4.98f, yc)
        val pelvicBaseTaper = 1f - 0.44f * smoothStep(-0.22f, -1.04f, yc)
        val rxTorso = ((1.36f + hipFlare + iliacFull + waistPinch + ribCage + thoraxSlim + shoulderSpan) *
            pelvicBaseTaper * shoulderTopTaper).coerceAtLeast(0.36f)

        val bellyDepth = 0.28f * exp(-((yc - 1.54f) / 1.05f).let { it * it }) +
            0.25f * smoothStep(1.65f, 3.15f, yc)
        val waistDepthDip = -0.050f * exp(-((yc - 2.42f) / 0.48f).let { it * it })
        // Sleek natural supine feminine ribcage tapering gently into slender clavicles & neck base
        val chestWallCurve = -0.18f * smoothStep(2.75f, 4.18f, yc) -
            0.25f * smoothStep(4.02f, 4.98f, yc)
        val pelvicDepthTaper = 1f - 0.30f * smoothStep(-0.30f, -1.04f, yc)
        val rzTorso = ((0.62f + bellyDepth + waistDepthDip + chestWallCurve) * pelvicDepthTaper).coerceAtLeast(0.32f)

        // Rounded capsule-capped super-elliptic torso column
        val capR = 0.16f
        val dRadTorso = sdEllipse2D(ax, z - zSpine, (rxTorso - capR).coerceAtLeast(0.14f), (rzTorso - capR).coerceAtLeast(0.14f))
        val dyTorso = max(y - 4.98f, -1.04f - y)
        var dTorso = sqrt(max(dRadTorso, 0f).let { it * it } + max(dyTorso, 0f).let { it * it }) +
            min(max(dRadTorso, dyTorso), 0f) - capR

        // Subtle anatomical abdominal, sternal, clavicle & dorsal relief
        if (y > 0.15f && y < 5.14f && ax < 1.65f) {
            val frontW = smoothStep(-0.08f, 0.34f, z - zSpine)
            if (frontW > 0.001f) {
                val navelDx = x / 0.072f
                val navelDy = (y - 2.02f) / 0.092f
                val navelR2 = navelDx * navelDx + navelDy * navelDy
                val navelDimple = 0.056f * exp(-navelR2 * 2.1f)
                val navelHood = -0.017f * exp(-((x / 0.10f).let { it * it } + ((y - 2.12f) / 0.052f).let { it * it }))
                val lineaAlba = 0.015f * exp(-((x / 0.075f).let { it * it })) * smoothBell(y, 1.30f, 3.05f, 0.25f)
                val iliacCrest = -0.042f * exp(-(((ax - 1.12f) / 0.38f).let { it * it } + ((y - 1.10f) / 0.48f).let { it * it }))
                // Delicate sternum midline, graceful S-curved feminine collarbones, soft supraclavicular hollow & suprasternal notch
                val sternalHollow = 0.014f * exp(-((x / 0.14f).let { it * it })) * smoothBell(y, 3.36f, 4.60f, 0.24f)
                val clavY = 4.74f - 0.05f * smoothStep(0.28f, 1.08f, ax) + 0.022f * sin(ax * 2.8f)
                val clavicleRidge = -0.016f * exp(-((y - clavY) / 0.060f).let { it * it }) * smoothBell(ax, 0.16f, 1.08f, 0.15f)
                val supraClavHollow = 0.018f * exp(-((y - (clavY + 0.13f)) / 0.090f).let { it * it }) * smoothBell(ax, 0.26f, 0.96f, 0.16f)
                val sternalNotch = 0.022f * exp(-((x / 0.11f).let { it * it } + ((y - 4.82f) / 0.09f).let { it * it }))
                dTorso += (navelDimple + navelHood + lineaAlba + iliacCrest + sternalHollow + clavicleRidge + supraClavHollow + sternalNotch) * frontW
            }
            val backW = smoothStep(0.10f, 0.48f, zSpine - z)
            if (backW > 0.001f) {
                val spineGroove = 0.028f * exp(-((x / 0.14f).let { it * it })) * smoothStep(0.25f, 4.65f, y)
                dTorso += spineGroove * backW
            }
        }

        // ---------------------------------------------------------------------
        // 2. DUAL-HEMISPHERE GLUTEAL MASS (SEAMLESSLY FUSED WITH PELVIS & THIGHS)
        // ---------------------------------------------------------------------
        val dGlute = sdEllipsoid(
            px = ax - 0.78f,
            py = y - (-0.54f),
            pz = z - (-0.65f),
            rx = 0.88f,
            ry = 0.66f,
            rz = 0.56f
        )
        // Natural gluteal cleft cradling the lower anus (-0.86) and curving up the dorsal midline
        val cleftMask = smoothStep(-0.42f, -0.88f, y) * (1f - smoothStep(0.16f, 0.42f, z + 0.42f))
        val cleftIndent = 0.095f * exp(-((x / 0.105f).let { it * it })) * cleftMask
        val dPelvis = smin(dTorso, dGlute, 0.38f) + cleftIndent

        // ---------------------------------------------------------------------
        // 3. TWO-BONE SCULPTED LEGS (SYMMETRICALLY EVALUATED IN +X HALF-SPACE)
        // ---------------------------------------------------------------------
        // Hip core H sits cleanly beside lower mons (+0.26), femur sweeps smoothly to crisp knee K,
        // straight tibia descends through shapely calf to slender ankle A & grounded foot!
        val hx = 1.12f; val hy = 0.26f; val hz = -0.22f
        val kx = 1.90f; val ky = -2.19f; val kz = 2.01f
        val axJ = 1.68f; val ayJ = -4.24f; val azJ = -0.94f

        // 3A. Femur (Hip -> Knee) with sleek convex organic thigh profile & subtle musculature
        val fdx = kx - hx; val fdy = ky - hy; val fdz = kz - hz
        val fLen2 = fdx * fdx + fdy * fdy + fdz * fdz
        val hFem = (((ax - hx) * fdx + (y - hy) * fdy + (z - hz) * fdz) / fLen2).coerceIn(0f, 1f)
        val fpx = ax - (hx + fdx * hFem)
        val fpy = y - (hy + fdy * hFem)
        val fpz = z - (hz + fdz * hFem)
        val fDist = sqrt(fpx * fpx + fpy * fpy + fpz * fpz)
        val femDrop = smoothStep(0.14f, 1.0f, hFem)
        var rFem = 0.72f - 0.26f * femDrop * femDrop
        // Subtle anterior quadriceps fullness & smooth, sleek posterior hamstring contour (zero lump!)
        val midThighW = smoothBell(hFem, 0.14f, 0.78f, 0.16f)
        if (midThighW > 0.001f && fDist > 1e-4f) {
            val antFactor = (fpz / fDist).coerceIn(-1f, 1f)
            rFem += max(0f, antFactor) * 0.036f * midThighW + max(0f, -antFactor) * 0.022f * midThighW
        }
        val dFemur = fDist - rFem

        // 3B. Sleek infragluteal saddle strictly bridging buttock cheek into upper hamstring (zero mid-thigh protrusion!)
        val dGluteBridge = sdCapsule(
            ax, y, z,
            0.96f, -0.44f, -0.58f,
            1.28f, -0.66f, 0.06f,
            0.54f, 0.46f
        )

        // 3C. Crisp anatomical knee (patella dome; solid watertight natural popliteal bend with ZERO cutout hole!)
        val dPatella = sdEllipsoid(
            px = ax - 1.90f,
            py = y - (-2.23f),
            pz = z - 2.18f,
            rx = 0.24f,
            ry = 0.26f,
            rz = 0.22f
        )

        // 3D. Tibia + sleek bone-aligned gastrocnemius calf contour tapering smoothly to slender ankle (zero pointy bump!)
        val tdx = axJ - kx; val tdy = ayJ - ky; val tdz = azJ - kz
        val tLen2 = tdx * tdx + tdy * tdy + tdz * tdz
        val hTib = (((ax - kx) * tdx + (y - ky) * tdy + (z - kz) * tdz) / tLen2).coerceIn(0f, 1f)
        val tpx = ax - (kx + tdx * hTib)
        val tpy = y - (ky + tdy * hTib)
        val tpz = z - (kz + tdz * hTib)
        val tDist = sqrt(tpx * tpx + tpy * tpy + tpz * tpz)
        val calfBelly = exp(-((hTib - 0.34f) / 0.24f).let { it * it })
        var rTib = 0.44f - 0.205f * smoothStep(0.14f, 0.96f, hTib) + 0.042f * calfBelly
        if (calfBelly > 0.001f && tDist > 1e-4f) {
            // Posterior unit direction perpendicular to the diagonal tibia bone (smooth, elongated gastrocnemius curve)
            val postFactor = ((tpy * 0.819f - tpz * 0.570f) / tDist).coerceIn(-1f, 1f)
            rTib += max(0f, postFactor) * 0.062f * calfBelly
        }
        val dTibia = tDist - rTib

        val dUpperLeg = smin(dFemur, dGluteBridge, 0.24f)
        var dLeg = smin(dUpperLeg, dTibia, 0.16f)
        dLeg = smin(dLeg, dPatella, 0.12f)

        // ---------------------------------------------------------------------
        // 4. ANATOMICAL SCULPTED FEET (ASYMMETRIC MALLEOLI -> SLEEK INSTEP -> ARCH -> OBLIQUE METATARSAL BALL)
        //    Pairs seamlessly with the 10 individually sculpted 3D toes in buildBlossomToesMesh()!
        // ---------------------------------------------------------------------
        // Subtle, anatomically tucked feminine malleoli (medial slightly higher & anterior; lateral slightly lower & posterior)
        val dMedialMalleolus = sdEllipsoid(
            px = ax - 1.575f,
            py = y - (-4.17f),
            pz = z - (-0.86f),
            rx = 0.104f,
            ry = 0.118f,
            rz = 0.106f
        )
        val dLateralMalleolus = sdEllipsoid(
            px = ax - 1.785f,
            py = y - (-4.27f),
            pz = z - (-0.90f),
            rx = 0.100f,
            ry = 0.116f,
            rz = 0.104f
        )
        val dHeel = sdEllipsoid(
            px = ax - 1.68f,
            py = y - (-4.06f),
            pz = z - (-0.97f),
            rx = 0.190f,
            ry = 0.210f,
            rz = 0.174f
        )
        // Graceful convex feminine dorsal instep tapering smoothly from ankle crease through midfoot (zero dorsal hollow!)
        val dInstep = sdCapsule(
            ax, y, z,
            1.68f, -4.22f, -0.89f,
            1.81f, -4.88f, -0.972f,
            0.194f, 0.178f
        )
        // Central dorsal metatarsal bridge filling rays 2-4 cleanly so the top of the foot is smoothly convex with zero dimple
        val dCenterMetatarsal = sdCapsule(
            ax, y, z,
            1.76f, -4.56f, -0.955f,
            1.90f, -5.14f, -1.022f,
            0.168f, 0.132f
        )
        // Natural medial & lateral metatarsal rays fanning organically to a graceful oblique toe line
        val dMedialRay = sdCapsule(
            ax, y, z,
            1.71f, -4.64f, -0.985f,
            1.70f, -5.22f, -1.032f,
            0.156f, 0.126f
        )
        val dLateralRay = sdCapsule(
            ax, y, z,
            1.84f, -4.60f, -0.990f,
            2.08f, -5.04f, -1.045f,
            0.146f, 0.108f
        )
        // Transverse metatarsal ball bridging hallux base cleanly to 5th-toe base
        val dMetatarsalPad = sdCapsule(
            ax, y, z,
            1.70f, -5.21f, -1.032f,
            2.08f, -5.03f, -1.045f,
            0.124f, 0.104f
        )
        // Graceful medial longitudinal foot arch underneath + subtle dorsal extensor contour
        val dArchCut = sdEllipsoid(
            px = ax - 1.56f,
            py = y - (-4.68f),
            pz = z - (-1.20f),
            rx = 0.185f,
            ry = 0.35f,
            rz = 0.112f
        )
        var dFoot = smin(dHeel, dInstep, 0.11f)
        dFoot = smin(dFoot, dMedialMalleolus, 0.115f)
        dFoot = smin(dFoot, dLateralMalleolus, 0.115f)
        val dForefootRays = smin(smin(dMedialRay, dCenterMetatarsal, 0.14f), dLateralRay, 0.14f)
        dFoot = smin(dFoot, dForefootRays, 0.13f)
        dFoot = smin(dFoot, dMetatarsalPad, 0.115f)
        dFoot = smax(dFoot, -dArchCut, 0.065f)

        // Seamless ankle-to-foot fusion
        val dFullLimb = smin(dLeg, dFoot, 0.125f)

        // ---------------------------------------------------------------------
        // 5. GRACEFUL FEMININE SHOULDERS, FULLER ANATOMICAL ARMS, OVAL WRISTS, SCULPTED PALMS & NECK
        //    Natural bicep/forearm fullness proportional to thighs & torso, smooth wrist-to-palm arch, ZERO hip webbing!
        // ---------------------------------------------------------------------
        val zShoulder = mattressSurfaceZ(4.54f) + 0.45f
        val zElbow = mattressSurfaceZ(2.50f) + 0.36f
        val zWrist = mattressSurfaceZ(0.82f) + 0.195f
        val zPalm = mattressSurfaceZ(0.36f) + 0.185f

        val zNeckBase = mattressSurfaceZ(4.86f) + 0.56f
        val zNeckTop = mattressSurfaceZ(5.78f) + 0.80f

        // Smooth diagonal feminine trapezius curving softly from lower neck into rounded shoulder (zero horizontal step!)
        val dTrapezius = sdCapsule(
            ax, y, z,
            0.30f, 5.10f, zNeckBase - 0.10f,
            1.25f, 4.54f, zShoulder - 0.04f,
            0.25f, 0.28f
        )
        // Low-profile soft feminine deltoid seamlessly tucked beside upper chest (NO armor pauldron bulge!)
        val dDeltoid = sdEllipsoid(
            px = ax - 1.29f,
            py = y - 4.50f,
            pz = z - zShoulder,
            rx = 0.26f,
            ry = 0.36f,
            rz = 0.29f
        )
        // Anatomically proportionate upper arm with natural open armpit-to-ribcage gap below axilla (ZERO ribcage webbing!)
        val uax0 = 1.33f; val uay0 = 4.40f; val uaz0 = zShoulder
        val uax1 = 2.14f; val uay1 = 2.50f; val uaz1 = zElbow
        val uadx = uax1 - uax0; val uady = uay1 - uay0; val uadz = uaz1 - uaz0
        val uaLen2 = uadx * uadx + uady * uady + uadz * uadz
        val hUpperArm = (((ax - uax0) * uadx + (y - uay0) * uady + (z - uaz0) * uadz) / uaLen2).coerceIn(0f, 1f)
        val uapx = ax - (uax0 + uadx * hUpperArm)
        val uapy = y - (uay0 + uady * hUpperArm)
        val uapz = z - (uaz0 + uadz * hUpperArm)
        val uaDist = sqrt(uapx * uapx + uapy * uapy + uapz * uapz)
        val bicepFullness = exp(-((hUpperArm - 0.46f) / 0.27f).let { it * it })
        val rUpperArm = 0.340f - 0.018f * hUpperArm + 0.038f * bicepFullness
        val dUpperArm = uaDist - rUpperArm

        // Natural muscular brachioradialis forearm tapering smoothly into an oval feminine wrist (zero stick pinch or wrist lump!)
        val fax0 = uax1; val fay0 = uay1; val faz0 = uaz1
        val fax1 = 2.24f; val fay1 = 0.82f; val faz1 = zWrist
        val fadx = fax1 - fax0; val fady = fay1 - fay0; val fadz = faz1 - faz0
        val faLen2 = fadx * fadx + fady * fady + fadz * fadz
        val hForearm = (((ax - fax0) * fadx + (y - fay0) * fady + (z - faz0) * fadz) / faLen2).coerceIn(0f, 1f)
        val fapx = ax - (fax0 + fadx * hForearm)
        val fapy = y - (fay0 + fady * hForearm)
        val fapz = z - (faz0 + fadz * hForearm)
        val faDist = sqrt(fapx * fapx + fapy * fapy + fapz * fapz)
        val forearmBelly = exp(-((hForearm - 0.24f) / 0.22f).let { it * it })
        val rForearmBase = 0.342f - 0.148f * smoothStep(0.16f, 1.0f, hForearm) + 0.036f * forearmBelly
        val distalOvalW = smoothStep(0.42f, 1.0f, hForearm)
        val rForearm = if (distalOvalW > 0.001f && faDist > 1e-4f) {
            val zComp = abs(fapz / faDist)
            rForearmBase * (1f - 0.29f * distalOvalW * zComp * zComp)
        } else {
            rForearmBase
        }
        val dForearm = faDist - rForearm

        // Sleek tapered wrist & compact interior carpal core (high-res sculpted parametric palm mesh wraps flush outside with zero voxel blobbiness!)
        val dWristBridge = sdEllipsoid(
            px = ax - 2.26f,
            py = y - 0.66f,
            pz = z - (zWrist * 0.5f + zPalm * 0.5f),
            rx = 0.150f,
            ry = 0.185f,
            rz = 0.112f
        )
        val dPalmCore = sdEllipsoid(
            px = ax - 2.30f,
            py = y - 0.42f,
            pz = z - zPalm,
            rx = 0.132f,
            ry = 0.230f,
            rz = 0.090f
        )
        val dPalm = smin(dPalmCore, dWristBridge, 0.080f)
        val dArmChain = smin(
            smin(dDeltoid, dUpperArm, 0.24f),
            smin(dForearm, dPalm, 0.085f),
            0.16f
        )
        val dShoulderArm = smin(dTrapezius, dArmChain, 0.24f)

        // Classical-length graceful feminine cervical neck rising cleanly behind the chin into the skull base
        var dNeck = sdCapsule(
            ax, y, z,
            0f, 4.84f, zNeckBase,
            0f, 5.78f, zNeckTop,
            0.42f, 0.39f
        )
        // Delicate subtle sternocleidomastoid (SCM) V-contour framing the anterior throat gently
        if (y in 4.78f..5.68f && ax < 0.52f) {
            val neckT = ((y - 4.84f) / 0.94f).coerceIn(0f, 1f)
            val scmTargetX = 0.11f + 0.21f * neckT
            val scmRidge = -0.012f * exp(-((ax - scmTargetX) / 0.065f).let { it * it }) *
                smoothBell(y, 4.88f, 5.56f, 0.10f) * smoothStep(-0.08f, 0.22f, z - (zNeckBase + (zNeckTop - zNeckBase) * neckT))
            dNeck += scmRidge
        }

        // ---------------------------------------------------------------------
        // 6. UNIFIED SEAMLESS FULL-BODY FUSION + EXAM MATTRESS SOFT CONTACT
        // ---------------------------------------------------------------------
        var dBody = smin(dPelvis, dFullLimb, 0.42f)
        // Smooth shoulder/axilla fusion at true anatomical armpit (y=3.84..4.40 ONLY); crisp open separation along lateral ribcage & waist!
        val kArmTorso = 0.22f * smoothStep(3.84f, 4.40f, y) + 0.002f
        dBody = smin(dBody, dShoulderArm, kArmTorso)
        dBody = smin(dBody, dNeck, 0.16f)

        // Softly flatten posterior torso, buttocks, arms, palms & foot soles onto the reclined exam mattress
        val matPlane = (mattressSurfaceZ(y) + 0.005f) - z
        dBody = smax(dBody, matPlane, 0.12f)

        return dBody
    }

    /**
     * Evaluates a smooth, C1-continuous `[0, 1]` arm-skinning ownership weight comparing Blossom's
     * exact arm-chain SDF (`deltoid + upperArm + forearm + palm`) against her torso/ribcage/trapezius SDF.
     *
     * Eliminates hard geometric cutoffs across the medial-posterior upper arm so ZERO upper-arm
     * vertices are left pinned to the exam table when lifting the arms, while keeping the torso,
     * breasts, neck, and legs 100% anchored.
     */
    fun evaluateArmVsTorsoWeight(x: Float, y: Float, z: Float): Float {
        val ax = abs(x)
        if (ax < 0.96f || y < -0.58f || y > 5.04f) return 0f
        // Strictly disjoint from LegSkinCache (legs sit inside y <= 0.44 and (ax <= 1.86 or z >= -0.35))
        if (y <= 0.44f && (ax <= 1.86f || z >= -0.35f)) return 0f

        // Exact Torso + Trapezius SDF (matching evaluateBodySdf)
        val yc = y.coerceIn(-1.04f, 4.98f)
        val matY = mattressSurfaceZ(yc)
        val upperFill = smoothStep(0.62f, 2.95f, yc)
        val perinealRollZ = -0.34f * smoothStep(-0.34f, -1.04f, yc)
        val thoraxSettle = -0.34f * smoothStep(2.55f, 4.86f, yc)
        val zSpine = (matY + 0.56f) + 0.28f * upperFill + perinealRollZ + thoraxSettle

        val hipFlare = 0.36f * exp(-((yc - 0.16f) / 1.12f).let { it * it })
        val iliacFull = 0.14f * exp(-((yc - 1.08f) / 0.68f).let { it * it })
        val waistPinch = -0.22f * exp(-((yc - 2.48f) / 0.62f).let { it * it })
        val ribCage = 0.05f * exp(-((yc - 3.92f) / 0.78f).let { it * it })
        val thoraxSlim = -0.14f * smoothStep(2.55f, 3.65f, yc)
        val shoulderSpan = 0.04f * exp(-((yc - 4.46f) / 0.44f).let { it * it })
        val shoulderTopTaper = 1f - 0.72f * smoothStep(4.50f, 4.98f, yc)
        val pelvicBaseTaper = 1f - 0.44f * smoothStep(-0.22f, -1.04f, yc)
        val rxTorso = ((1.36f + hipFlare + iliacFull + waistPinch + ribCage + thoraxSlim + shoulderSpan) *
            pelvicBaseTaper * shoulderTopTaper).coerceAtLeast(0.36f)

        val bellyDepth = 0.28f * exp(-((yc - 1.54f) / 1.05f).let { it * it }) +
            0.25f * smoothStep(1.65f, 3.15f, yc)
        val waistDepthDip = -0.050f * exp(-((yc - 2.42f) / 0.48f).let { it * it })
        val chestWallCurve = -0.18f * smoothStep(2.75f, 4.18f, yc) -
            0.25f * smoothStep(4.02f, 4.98f, yc)
        val pelvicDepthTaper = 1f - 0.30f * smoothStep(-0.30f, -1.04f, yc)
        val rzTorso = ((0.62f + bellyDepth + waistDepthDip + chestWallCurve) * pelvicDepthTaper).coerceAtLeast(0.32f)

        val capR = 0.16f
        val dRadTorso = sdEllipse2D(ax, z - zSpine, (rxTorso - capR).coerceAtLeast(0.14f), (rzTorso - capR).coerceAtLeast(0.14f))
        val dyTorso = max(y - 4.98f, -1.04f - y)
        val dTorso = sqrt(max(dRadTorso, 0f).let { it * it } + max(dyTorso, 0f).let { it * it }) +
            min(max(dRadTorso, dyTorso), 0f) - capR

        val zShoulder = mattressSurfaceZ(4.54f) + 0.45f
        val zElbow = mattressSurfaceZ(2.50f) + 0.36f
        val zWrist = mattressSurfaceZ(0.82f) + 0.195f
        val zPalm = mattressSurfaceZ(0.36f) + 0.185f
        val zNeckBase = mattressSurfaceZ(4.86f) + 0.56f

        val dTrapezius = sdCapsule(
            ax, y, z,
            0.30f, 5.10f, zNeckBase - 0.10f,
            1.25f, 4.54f, zShoulder - 0.04f,
            0.25f, 0.28f
        )
        val dDeltoid = sdEllipsoid(
            px = ax - 1.29f,
            py = y - 4.50f,
            pz = z - zShoulder,
            rx = 0.26f,
            ry = 0.36f,
            rz = 0.29f
        )

        val uax0 = 1.33f; val uay0 = 4.40f; val uaz0 = zShoulder
        val uax1 = 2.14f; val uay1 = 2.50f; val uaz1 = zElbow
        val uadx = uax1 - uax0; val uady = uay1 - uay0; val uadz = uaz1 - uaz0
        val uaLen2 = uadx * uadx + uady * uady + uadz * uadz
        val hUpperArm = (((ax - uax0) * uadx + (y - uay0) * uady + (z - uaz0) * uadz) / uaLen2).coerceIn(0f, 1f)
        val uapx = ax - (uax0 + uadx * hUpperArm)
        val uapy = y - (uay0 + uady * hUpperArm)
        val uapz = z - (uaz0 + uadz * hUpperArm)
        val uaDist = sqrt(uapx * uapx + uapy * uapy + uapz * uapz)
        val bicepFullness = exp(-((hUpperArm - 0.46f) / 0.27f).let { it * it })
        val rUpperArm = 0.340f - 0.018f * hUpperArm + 0.038f * bicepFullness
        val dUpperArm = uaDist - rUpperArm

        val fax0 = uax1; val fay0 = uay1; val faz0 = uaz1
        val fax1 = 2.24f; val fay1 = 0.82f; val faz1 = zWrist
        val fadx = fax1 - fax0; val fady = fay1 - fay0; val fadz = faz1 - faz0
        val faLen2 = fadx * fadx + fady * fady + fadz * fadz
        val hForearm = (((ax - fax0) * fadx + (y - fay0) * fady + (z - faz0) * fadz) / faLen2).coerceIn(0f, 1f)
        val fapx = ax - (fax0 + fadx * hForearm)
        val fapy = y - (fay0 + fady * hForearm)
        val fapz = z - (faz0 + fadz * hForearm)
        val faDist = sqrt(fapx * fapx + fapy * fapy + fapz * fapz)
        val forearmBelly = exp(-((hForearm - 0.24f) / 0.22f).let { it * it })
        val rForearmBase = 0.342f - 0.148f * smoothStep(0.16f, 1.0f, hForearm) + 0.036f * forearmBelly
        val distalOvalW = smoothStep(0.42f, 1.0f, hForearm)
        val rForearm = if (distalOvalW > 0.001f && faDist > 1e-4f) {
            val zComp = abs(fapz / faDist)
            rForearmBase * (1f - 0.29f * distalOvalW * zComp * zComp)
        } else {
            rForearmBase
        }
        val dForearm = faDist - rForearm

        val dWristBridge = sdEllipsoid(
            px = ax - 2.26f,
            py = y - 0.66f,
            pz = z - (zWrist * 0.5f + zPalm * 0.5f),
            rx = 0.150f,
            ry = 0.185f,
            rz = 0.112f
        )
        val dPalmCore = sdEllipsoid(
            px = ax - 2.30f,
            py = y - 0.42f,
            pz = z - zPalm,
            rx = 0.132f,
            ry = 0.230f,
            rz = 0.090f
        )
        val dPalm = smin(dPalmCore, dWristBridge, 0.080f)
        val dArmChain = smin(
            smin(dDeltoid, dUpperArm, 0.24f),
            smin(dForearm, dPalm, 0.085f),
            0.16f
        )

        val dTorsoAnchor = min(dTorso, dTrapezius)
        // Smooth SDF ownership transition across true axilla/shoulder crease (ZERO discontinuous cliff & ZERO ribcage-arm stretch!)
        val sdfWeight = smoothStep(+0.26f, -0.26f, dArmChain - dTorsoAnchor)
        val trapGuard = 1f - smoothStep(4.62f, 5.00f, y) * (1f - smoothStep(1.26f, 1.56f, ax))
        val farFade = 1f - smoothStep(0.28f, 0.56f, dArmChain)
        return (sdfWeight * trapGuard * farFade).coerceIn(0f, 1f)
    }

    /**
     * Exact central-difference gradient normal on the SDF surface.
     */
    fun evaluateBodyNormal(x: Float, y: Float, z: Float, eps: Float = 0.014f): FloatArray {
        val dx = evaluateBodySdf(x + eps, y, z) - evaluateBodySdf(x - eps, y, z)
        val dy = evaluateBodySdf(x, y + eps, z) - evaluateBodySdf(x, y - eps, z)
        val dz = evaluateBodySdf(x, y, z + eps) - evaluateBodySdf(x, y, z - eps)
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        return if (len > 1e-6f) {
            floatArrayOf(dx / len, dy / len, dz / len)
        } else {
            floatArrayOf(0f, 0f, 1f)
        }
    }

    /**
     * Evaluates consistent anatomical ambient occlusion and subtle skin pigment across the
     * unified SDF body so BOTH the Surface Nets mesh and the Ultra-HD vulva patch match 100%.
     */
    fun evaluateBodyMaterial(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Pair<Float, Float> {
        val ax = abs(x)
        val matZ = mattressSurfaceZ(y)

        // Contact AO where dorsal torso, buttocks & feet rest on the dark clinical exam mattress
        val matContact = (1f - smoothStep(0.02f, 0.24f, z - matZ)) * smoothStep(0.10f, 0.72f, -nz)
        val cushionAo = 1f - 0.34f * matContact

        // Navel cavity AO & subtle pigment
        val navelDx = x / 0.072f
        val navelDy = (y - 2.10f) / 0.092f
        val navelR2 = navelDx * navelDx + navelDy * navelDy
        val frontW = smoothStep(0.15f, 0.70f, nz)
        val navelAo = 1f - 0.44f * exp(-navelR2 * 2.4f) * frontW

        // Gluteal cleft AO
        val cleftMask = smoothStep(-0.34f, -0.82f, y) * (1f - smoothStep(0.05f, 0.45f, z + 0.35f))
        val cleftAo = 1f - 0.44f * exp(-((x / 0.11f).let { it * it })) * cleftMask

        // Subtle oblique inguinal crease tint & AO
        val ingLineY = 0.36f + (ax - 0.40f) * 0.74f
        val ingDip = exp(-((y - ingLineY) / 0.15f).let { it * it }) * smoothBell(ax, 0.42f, 1.24f, 0.24f) * frontW
        val ingAo = 1f - 0.08f * ingDip

        // Back-of-knee popliteal crease AO
        val popDx = (ax - 1.86f) / 0.36f
        val popDy = (y - (-2.15f)) / 0.34f
        val popDz = (z - 1.55f) / 0.36f
        val popMask = exp(-(popDx * popDx + popDy * popDy + popDz * popDz)) * smoothStep(0.05f, 0.65f, -nz)
        val popAo = 1f - 0.26f * popMask

        // Subtle upper-body anatomical AO (axillary folds, supraclavicular hollows, suprasternal notch, under-chin shadow & elbow crease)
        val axillaAo = 1f - 0.20f * exp(-(((ax - 1.18f) / 0.24f).let { it * it } + ((y - 4.28f) / 0.32f).let { it * it }))
        val notchAo = 1f - 0.15f * exp(-((x / 0.14f).let { it * it } + ((y - 4.82f) / 0.11f).let { it * it })) * frontW
        val supraClavAo = 1f - 0.10f * exp(-(((ax - 0.62f) / 0.34f).let { it * it } + ((y - 4.86f) / 0.10f).let { it * it })) * frontW
        val underChinAo = 1f - 0.26f * exp(-((x / 0.38f).let { it * it } + ((y - 5.44f) / 0.22f).let { it * it })) * frontW
        val elbowAo = 1f - 0.14f * exp(-(((ax - 1.80f) / 0.26f).let { it * it } + ((y - 2.70f) / 0.24f).let { it * it })) * frontW

        val ao = (cushionAo * navelAo * cleftAo * ingAo * popAo * axillaAo * notchAo * supraClavAo * underChinAo * elbowAo).coerceIn(0.14f, 1f)

        val kneeWarm = exp(-(((ax - 1.90f) / 0.42f).let { it * it } + ((y + 2.19f) / 0.42f).let { it * it } + ((z - 2.05f) / 0.42f).let { it * it })) * 0.09f
        val shoulderWarm = exp(-(((ax - 1.56f) / 0.40f).let { it * it } + ((y - 4.78f) / 0.42f).let { it * it })) * 0.05f
        val toeWarm = smoothStep(-5.15f, -5.65f, y) * 0.09f
        val pigment = (ingDip * 0.08f + exp(-navelR2 * 2.0f) * 0.16f * frontW + kneeWarm + shoulderWarm + toeWarm).coerceIn(0f, 1f)

        return Pair(ao, pigment)
    }

    /**
     * Exact ray-march + Newton-Raphson front-surface sampler on the unified SDF body
     * for any (x, y) inside the anterior vulva/perineum/anus region.
     */
    fun sampleBodySurfacePoint(x: Float, y: Float): SdfSurfaceSample {
        // Start comfortably in front of the anterior pelvis & inner thighs and sphere-trace backward along -Z
        var z = 1.40f
        for (step in 0 until 40) {
            val d = evaluateBodySdf(x, y, z)
            if (abs(d) < 0.0006f) break
            z -= d.coerceIn(-0.22f, 0.38f)
            if (z < -1.30f) {
                z = -1.12f
                break
            }
        }
        // 5 Newton-Raphson polish iterations along Z for < 0.0001mm surface precision
        for (it in 0 until 5) {
            val d = evaluateBodySdf(x, y, z)
            if (abs(d) < 0.0001f) break
            val dz = (evaluateBodySdf(x, y, z + 0.006f) - evaluateBodySdf(x, y, z - 0.006f)) / 0.012f
            if (abs(dz) > 0.10f) {
                z -= (d / dz).coerceIn(-0.08f, 0.08f)
            } else {
                z -= d * 0.5f
            }
        }
        val n = evaluateBodyNormal(x, y, z)
        val (ao, pigment) = evaluateBodyMaterial(x, y, z, n[0], n[1], n[2])
        return SdfSurfaceSample(z, n[0], n[1], n[2], ao, pigment)
    }

    /**
     * Extracts Blossom's single continuous watertight 3D lower body using two-pass narrow-band
     * Surface Nets + tangential Laplacian smoothing + exact SDF projection & gradient normals.
     */
    fun buildUnifiedBodyMesh(): DynamicGlMesh {
        val nx = ((MAX_X - MIN_X) / STEP).toInt()
        val ny = ((MAX_Y - MIN_Y) / STEP).toInt()
        val nz = ((MAX_Z - MIN_Z) / STEP).toInt()
        val gx = nx + 1
        val gy = ny + 1
        val gz = nz + 1

        // Pass 1: 2x coarse grid evaluation to skip empty air/deep interior quickly
        val cStep = STEP * 2f
        val cnx = (nx + 1) / 2 + 1
        val cny = (ny + 1) / 2 + 1
        val cnz = (nz + 1) / 2 + 1
        val coarse = FloatArray(cnx * cny * cnz)
        for (ck in 0 until cnz) {
            val pz = MIN_Z + ck * cStep
            val kOff = ck * cny * cnx
            for (cj in 0 until cny) {
                val py = MIN_Y + cj * cStep
                val jOff = kOff + cj * cnx
                for (ci in 0 until cnx) {
                    val px = MIN_X + ci * cStep
                    coarse[jOff + ci] = evaluateBodySdf(px, py, pz)
                }
            }
        }

        // Pass 2: Evaluate fine SDF only within narrow band around surface (|coarse| < 0.44)
        val grid = FloatArray(gx * gy * gz)
        val bandThreshold = cStep * 2.4f
        for (k in 0 until gz) {
            val pz = MIN_Z + k * STEP
            val ck = (k / 2).coerceAtMost(cnz - 1)
            val ck1 = min(ck + 1, cnz - 1)
            val kOff = k * gy * gx
            for (j in 0 until gy) {
                val py = MIN_Y + j * STEP
                val cj = (j / 2).coerceAtMost(cny - 1)
                val cj1 = min(cj + 1, cny - 1)
                val jOff = kOff + j * gx
                for (i in 0 until gx) {
                    val ci = (i / 2).coerceAtMost(cnx - 1)
                    val ci1 = min(ci + 1, cnx - 1)
                    val cVal = coarse[(ck * cny + cj) * cnx + ci]
                    val nearSurface = abs(cVal) < bandThreshold ||
                        abs(coarse[(ck * cny + cj) * cnx + ci1]) < bandThreshold ||
                        abs(coarse[(ck * cny + cj1) * cnx + ci]) < bandThreshold ||
                        abs(coarse[(ck1 * cny + cj) * cnx + ci]) < bandThreshold
                    grid[jOff + i] = if (nearSurface) {
                        evaluateBodySdf(MIN_X + i * STEP, py, pz)
                    } else {
                        cVal
                    }
                }
            }
        }

        fun gIdx(i: Int, j: Int, k: Int): Int = (k * gy + j) * gx + i
        fun cIdx(i: Int, j: Int, k: Int): Int = (k * ny + j) * nx + i

        // Step 3: Emit 1 Surface Nets vertex per sign-changing cell
        val cellVert = IntArray(nx * ny * nz) { -1 }
        val vxList = FloatArrayList(12000)
        val vyList = FloatArrayList(12000)
        val vzList = FloatArrayList(12000)

        val edgeA = intArrayOf(0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3)
        val edgeB = intArrayOf(1, 3, 5, 7, 2, 3, 6, 7, 4, 5, 6, 7)
        val cornerDx = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1)
        val cornerDy = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
        val cornerDz = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
        val cv = FloatArray(8)

        for (k in 0 until nz) {
            val z0 = MIN_Z + k * STEP
            for (j in 0 until ny) {
                val y0 = MIN_Y + j * STEP
                for (i in 0 until nx) {
                    val v0 = grid[gIdx(i, j, k)]
                    val v1 = grid[gIdx(i + 1, j, k)]
                    val v2 = grid[gIdx(i, j + 1, k)]
                    val v3 = grid[gIdx(i + 1, j + 1, k)]
                    val v4 = grid[gIdx(i, j, k + 1)]
                    val v5 = grid[gIdx(i + 1, j, k + 1)]
                    val v6 = grid[gIdx(i, j + 1, k + 1)]
                    val v7 = grid[gIdx(i + 1, j + 1, k + 1)]

                    var insideCount = 0
                    if (v0 < 0f) insideCount++
                    if (v1 < 0f) insideCount++
                    if (v2 < 0f) insideCount++
                    if (v3 < 0f) insideCount++
                    if (v4 < 0f) insideCount++
                    if (v5 < 0f) insideCount++
                    if (v6 < 0f) insideCount++
                    if (v7 < 0f) insideCount++
                    if (insideCount == 0 || insideCount == 8) continue

                    cv[0] = v0; cv[1] = v1; cv[2] = v2; cv[3] = v3
                    cv[4] = v4; cv[5] = v5; cv[6] = v6; cv[7] = v7

                    var sx = 0f
                    var sy = 0f
                    var sz = 0f
                    var crossCount = 0
                    for (e in 0 until 12) {
                        val a = edgeA[e]
                        val b = edgeB[e]
                        val da = cv[a]
                        val db = cv[b]
                        if ((da < 0f) != (db < 0f)) {
                            val t = (da / (da - db)).coerceIn(0.05f, 0.95f)
                            sx += cornerDx[a] + (cornerDx[b] - cornerDx[a]) * t
                            sy += cornerDy[a] + (cornerDy[b] - cornerDy[a]) * t
                            sz += cornerDz[a] + (cornerDz[b] - cornerDz[a]) * t
                            crossCount++
                        }
                    }
                    val invC = 1f / crossCount.coerceAtLeast(1)
                    val x0 = MIN_X + i * STEP
                    val vId = vxList.size
                    vxList.add(x0 + sx * invC * STEP)
                    vyList.add(y0 + sy * invC * STEP)
                    vzList.add(z0 + sz * invC * STEP)
                    cellVert[cIdx(i, j, k)] = vId
                }
            }
        }

        val vCount = vxList.size
        val vx = vxList.toTrimmedArray()
        val vy = vyList.toTrimmedArray()
        val vz = vzList.toTrimmedArray()

        // Step 4: Collect consistently-oriented outward quads around every sign-changing interior edge
        val quadList = IntArrayList(vCount * 4)

        // X-axis interior edges: (i, j, k) -> (i+1, j, k)
        for (k in 1 until nz) {
            for (j in 1 until ny) {
                for (i in 0 until nx) {
                    val d0 = grid[gIdx(i, j, k)]
                    val d1 = grid[gIdx(i + 1, j, k)]
                    if ((d0 < 0f) == (d1 < 0f)) continue
                    val q00 = cellVert[cIdx(i, j - 1, k - 1)]
                    val q10 = cellVert[cIdx(i, j, k - 1)]
                    val q11 = cellVert[cIdx(i, j, k)]
                    val q01 = cellVert[cIdx(i, j - 1, k)]
                    if (q00 < 0 || q10 < 0 || q11 < 0 || q01 < 0) continue
                    if (d0 < 0f) {
                        quadList.add4(q00, q10, q11, q01)
                    } else {
                        quadList.add4(q00, q01, q11, q10)
                    }
                }
            }
        }

        // Y-axis interior edges: (i, j, k) -> (i, j+1, k)
        for (k in 1 until nz) {
            for (j in 0 until ny) {
                for (i in 1 until nx) {
                    val d0 = grid[gIdx(i, j, k)]
                    val d1 = grid[gIdx(i, j + 1, k)]
                    if ((d0 < 0f) == (d1 < 0f)) continue
                    val q00 = cellVert[cIdx(i - 1, j, k - 1)]
                    val q01 = cellVert[cIdx(i - 1, j, k)]
                    val q11 = cellVert[cIdx(i, j, k)]
                    val q10 = cellVert[cIdx(i, j, k - 1)]
                    if (q00 < 0 || q01 < 0 || q11 < 0 || q10 < 0) continue
                    if (d0 < 0f) {
                        quadList.add4(q00, q01, q11, q10)
                    } else {
                        quadList.add4(q00, q10, q11, q01)
                    }
                }
            }
        }

        // Z-axis interior edges: (i, j, k) -> (i, j, k+1)
        for (k in 0 until nz) {
            for (j in 1 until ny) {
                for (i in 1 until nx) {
                    val d0 = grid[gIdx(i, j, k)]
                    val d1 = grid[gIdx(i, j, k + 1)]
                    if ((d0 < 0f) == (d1 < 0f)) continue
                    val q00 = cellVert[cIdx(i - 1, j - 1, k)]
                    val q10 = cellVert[cIdx(i, j - 1, k)]
                    val q11 = cellVert[cIdx(i, j, k)]
                    val q01 = cellVert[cIdx(i - 1, j, k)]
                    if (q00 < 0 || q10 < 0 || q11 < 0 || q01 < 0) continue
                    if (d0 < 0f) {
                        quadList.add4(q00, q10, q11, q01)
                    } else {
                        quadList.add4(q00, q01, q11, q10)
                    }
                }
            }
        }

        val quadCount = quadList.size / 4
        val quads = quadList.data

        // Step 5: Two passes of neighbor Laplacian smoothing + exact SDF=0 Newton projection!
        // Removes every trace of voxel stair-stepping while keeping exact sculpted anatomical shape!
        val accX = FloatArray(vCount)
        val accY = FloatArray(vCount)
        val accZ = FloatArray(vCount)
        val accN = IntArray(vCount)

        repeat(2) {
            accX.fill(0f)
            accY.fill(0f)
            accZ.fill(0f)
            accN.fill(0)
            for (q in 0 until quadCount) {
                val base = q * 4
                val a = quads[base]
                val b = quads[base + 1]
                val c = quads[base + 2]
                val d = quads[base + 3]

                accX[a] += vx[b] + vx[d]; accY[a] += vy[b] + vy[d]; accZ[a] += vz[b] + vz[d]; accN[a] += 2
                accX[b] += vx[a] + vx[c]; accY[b] += vy[a] + vy[c]; accZ[b] += vz[a] + vz[c]; accN[b] += 2
                accX[c] += vx[b] + vx[d]; accY[c] += vy[b] + vy[d]; accZ[c] += vz[b] + vz[d]; accN[c] += 2
                accX[d] += vx[c] + vx[a]; accY[d] += vy[c] + vy[a]; accZ[d] += vz[c] + vz[a]; accN[d] += 2
            }
            for (v in 0 until vCount) {
                val cnt = accN[v]
                if (cnt > 0) {
                    val inv = 1f / cnt
                    var px = vx[v] * 0.55f + (accX[v] * inv) * 0.45f
                    var py = vy[v] * 0.55f + (accY[v] * inv) * 0.45f
                    var pz = vz[v] * 0.55f + (accZ[v] * inv) * 0.45f
                    // Project back onto exact SDF = 0 isosurface
                    val dVal = evaluateBodySdf(px, py, pz)
                    val norm = evaluateBodyNormal(px, py, pz, eps = 0.016f)
                    val stepBack = dVal.coerceIn(-0.06f, 0.06f)
                    px -= norm[0] * stepBack
                    py -= norm[1] * stepBack
                    pz -= norm[2] * stepBack
                    vx[v] = px
                    vy[v] = py
                    vz[v] = pz
                }
            }
        }

        // Step 6: Compute exact analytical SDF normals + body materials and filter interior vulva-core quads
        val vNx = FloatArray(vCount)
        val vNy = FloatArray(vCount)
        val vNz = FloatArray(vCount)
        val insideVulvaCore = BooleanArray(vCount)

        for (v in 0 until vCount) {
            val px = vx[v]
            val py = vy[v]
            val pz = vz[v]
            val norm = evaluateBodyNormal(px, py, pz, eps = 0.014f)
            vNx[v] = norm[0]
            vNy[v] = norm[1]
            vNz[v] = norm[2]

            val ovalR = sqrt((px / PATCH_RX).let { it * it } + ((py - PATCH_CENTER_Y) / PATCH_RY).let { it * it })
            // Smoothly recess the SDF body collar behind the Ultra-HD vulva patch across ovalR < 0.94 so
            // ZERO coarse SDF voxel blocks can EVER poke through negative groin/vestibule/anus dips!
            val isAnteriorFace = pz > -0.85f && norm[2] > 0.08f
            if (isAnteriorFace && ovalR < 0.94f) {
                val collarRecess = 0.090f * (1f - smoothStep(0.76f, 0.94f, ovalR))
                vx[v] = px - norm[0] * collarRecess
                vy[v] = py - norm[1] * collarRecess
                vz[v] = pz - norm[2] * collarRecess
            }
            // Omit quads strictly inside the recessed vulva & perianal core
            insideVulvaCore[v] = ovalR < 0.80f && isAnteriorFace
        }

        val triIndices = ShortArrayList(quadCount * 6)
        for (q in 0 until quadCount) {
            val base = q * 4
            val a = quads[base]
            val b = quads[base + 1]
            val c = quads[base + 2]
            val d = quads[base + 3]
            if (insideVulvaCore[a] && insideVulvaCore[b] && insideVulvaCore[c] && insideVulvaCore[d]) {
                continue
            }
            // Split along shorter diagonal for cleaner organic shading
            val dAC = sqDist(vx[a], vy[a], vz[a], vx[c], vy[c], vz[c])
            val dBD = sqDist(vx[b], vy[b], vz[b], vx[d], vy[d], vz[d])
            if (dAC <= dBD) {
                triIndices.add6(a, b, c, a, c, d)
            } else {
                triIndices.add6(a, b, d, b, c, d)
            }
        }

        val mesh = DynamicGlMesh(vCount, triIndices.size)
        mesh.setIndices(triIndices.toTrimmedArray())

        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        for (v in 0 until vCount) {
            val px = vx[v]
            val py = vy[v]
            val pz = vz[v]
            val nxV = vNx[v]
            val nyV = vNy[v]
            val nzV = vNz[v]
            val (ao, pigment) = evaluateBodyMaterial(px, py, pz, nxV, nyV, nzV)

            val idx = v * stride
            data[idx] = px
            data[idx + 1] = py
            data[idx + 2] = pz
            data[idx + 3] = nxV
            data[idx + 4] = nyV
            data[idx + 5] = nzV
            data[idx + 6] = 0f
            data[idx + 7] = 0.055f
            data[idx + 8] = ao
            data[idx + 9] = pigment
        }

        mesh.commitVertices()
        return mesh
    }

    internal data class BlossomToeSpec(
        val rootAx: Float,
        val rootY: Float,
        val rootZ: Float,
        val length: Float,
        val rLat: Float,
        val rZ: Float,
        val yawOut: Float,
        val archLift: Float
    )

    // Gracefully clustered feminine Egyptian toe cascade (natural hallux -> gently curled lesser toes, ZERO splayed pegs!)
    internal val BLOSSOM_TOE_SPECS: List<BlossomToeSpec> = listOf(
        BlossomToeSpec(rootAx = 1.676f, rootY = -5.22f, rootZ = -1.034f, length = 0.405f, rLat = 0.077f, rZ = 0.063f, yawOut = 0.085f, archLift = 0.012f),
        BlossomToeSpec(rootAx = 1.812f, rootY = -5.19f, rootZ = -1.040f, length = 0.375f, rLat = 0.049f, rZ = 0.049f, yawOut = 0.105f, archLift = 0.024f),
        BlossomToeSpec(rootAx = 1.918f, rootY = -5.14f, rootZ = -1.044f, length = 0.320f, rLat = 0.046f, rZ = 0.046f, yawOut = 0.115f, archLift = 0.025f),
        BlossomToeSpec(rootAx = 2.018f, rootY = -5.08f, rootZ = -1.048f, length = 0.268f, rLat = 0.043f, rZ = 0.043f, yawOut = 0.095f, archLift = 0.022f),
        BlossomToeSpec(rootAx = 2.114f, rootY = -5.01f, rootZ = -1.052f, length = 0.215f, rLat = 0.040f, rZ = 0.039f, yawOut = 0.045f, archLift = 0.017f)
    )

    /**
     * Builds all 10 individually sculpted anatomical 3D toes (5 per foot: Hallux through 5th toe)
     * in a single lightweight [DynamicGlMesh], seamlessly rooted along the oblique metatarsal line
     * with realistic interphalangeal knuckles, plump toe pads, and recessed dorsal nailbeds.
     */
    fun buildBlossomToesMesh(): DynamicGlMesh {
        val toeSpecs = BLOSSOM_TOE_SPECS

        val cols = 16
        val rows = 14
        val vertsPerToe = cols * rows
        val trisPerToe = (cols - 1) * (rows - 1) * 6
        val totalToes = toeSpecs.size * 2

        val mesh = DynamicGlMesh(vertsPerToe * totalToes, trisPerToe * totalToes)
        val indices = ShortArray(trisPerToe * totalToes)
        val data = mesh.vertexData
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val floorZ = BlossomBodyBuilder.MATTRESS_TOP_Z + 0.012f

        var toeIndex = 0
        var idxOut = 0
        val sides = floatArrayOf(-1f, +1f)

        for (side in sides) {
            for ((specIdx, spec) in toeSpecs.withIndex()) {
                val baseVert = toeIndex * vertsPerToe
                val isHallux = specIdx == 0

                // Emit strictly outward CCW grid indices for both left (+1) and right (-1) feet
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

                // Forward & lateral unit vectors in world space for this toe
                val fwdX = side * sin(spec.yawOut)
                val fwdY = -cos(spec.yawOut)
                val latX = side * cos(spec.yawOut)
                val latY = sin(spec.yawOut)

                for (r in 0 until rows) {
                    val s = r.toFloat() / (rows - 1)

                    // Plump, softly rounded human toe tip dome (starts at s=0.84 -> never pointy!) + sculpted IP knuckle & toe pad
                    val tipT = smoothStep(0.84f, 1.0f, s)
                    val tipAngle = tipT * (PI.toFloat() * 0.5f)
                    val tipScale = cos(tipAngle).coerceAtLeast(0.03f)
                    val rootFlare = 1f + (if (isHallux) 0.16f else 0.12f) * (1f - smoothStep(0.0f, 0.22f, s))
                    val phalanxNeck = 1f - 0.085f * exp(-((s - 0.34f) / 0.15f).let { it * it })
                    val ipKnuckle = 1f + (if (isHallux) 0.055f else 0.072f) * exp(-((s - 0.50f) / 0.09f).let { it * it })
                    val distalPad = 1f + (if (isHallux) 0.085f else 0.115f) * exp(-((s - 0.76f) / 0.14f).let { it * it })
                    val radScale = rootFlare * phalanxNeck * ipKnuckle * distalPad * tipScale

                    val along = -0.095f + s * (spec.length + 0.095f)
                    // Natural gentle plantar toe arch: lesser toes curve slightly downward at the IP joint onto plump soft toe pads
                    val curlZ = spec.archLift * sin(s * PI.toFloat()) -
                        (if (isHallux) 0.018f else 0.030f) * smoothStep(0.42f, 0.94f, s)
                    // Subtle natural medial nestling of lesser toes (4th & 5th tuck gently toward hallux)
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

                    for (c in 0 until cols) {
                        val u = c.toFloat() / (cols - 1)
                        val phi = u * (2f * PI.toFloat())
                        val cosP = cos(phi)
                        val sinP = sin(phi)

                        // Subtle dorsal nail plate indentation on distal upper face (s in 0.62..0.90)
                        val nailMask = smoothBell(s, 0.62f, 0.90f, 0.05f) * smoothStep(0.45f, 0.92f, sinP) *
                            (1f - smoothStep(0.55f, 0.88f, abs(cosP)))
                        val nailIndent = -0.0040f * nailMask
                        val knuckleBump = (if (isHallux) 0.0028f else 0.0038f) *
                            exp(-((s - 0.50f) / 0.075f).let { it * it }) * smoothStep(0.20f, 0.90f, sinP)

                        val localLat = spec.rLat * radScale * cosP
                        var localZ = spec.rZ * radScale * sinP + nailIndent + knuckleBump
                        // Soft plantar toe pad resting gently against the exam cushion
                        if (sinP < 0f) {
                            localZ *= (0.82f + 0.10f * smoothStep(0.60f, 0.90f, s))
                        }

                        val px = cx + latX * localLat
                        val py = cy + latY * localLat
                        val pz = (cz + localZ).coerceAtLeast(floorZ)

                        // Exact analytic outward dome normal (smoothly rotates to pure forward at tip apex — zero pinch!)
                        val radialW = cos(tipAngle)
                        val fwdW = sin(tipAngle)
                        var nx = latX * (cosP * radialW / spec.rLat) + fwdX * (fwdW / spec.rLat)
                        var ny = latY * (cosP * radialW / spec.rLat) + fwdY * (fwdW / spec.rLat)
                        var nz = sinP * radialW / spec.rZ
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                        nx /= nLen; ny /= nLen; nz /= nLen

                        val rootBlend = 1f - smoothStep(0.0f, 0.28f, s)
                        if (rootBlend > 0.001f) {
                            val sdfNorm = evaluateBodyNormal(px, py, pz, eps = 0.016f)
                            val mx = nx * (1f - rootBlend) + sdfNorm[0] * rootBlend
                            val my = ny * (1f - rootBlend) + sdfNorm[1] * rootBlend
                            val mz = nz * (1f - rootBlend) + sdfNorm[2] * rootBlend
                            val mLen = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-5f)
                            nx = mx / mLen; ny = my / mLen; nz = mz / mLen
                        }

                        // Soft interdigital crevice shadow + mattress contact shadow + subtle glossy nail sheen
                        val interdigitalAo = 1f - abs(cosP) * 0.24f * (1f - smoothStep(0.10f, 0.62f, s))
                        val soleAo = 1f - smoothStep(0.10f, 0.80f, -sinP) * 0.34f
                        val ao = (interdigitalAo * soleAo).coerceIn(0.36f, 1f)
                        val wetness = (0.055f + nailMask * 0.22f).coerceIn(0f, 0.45f)
                        val pigment = (0.08f + smoothStep(0.60f, 0.96f, s) * 0.08f + nailMask * 0.14f).coerceIn(0f, 1f)

                        val vIdx = (baseVert + r * cols + c) * stride
                        data[vIdx] = px
                        data[vIdx + 1] = py
                        data[vIdx + 2] = pz
                        data[vIdx + 3] = nx
                        data[vIdx + 4] = ny
                        data[vIdx + 5] = nz
                        data[vIdx + 6] = nailMask * 0.16f // gentle natural rosy nail tint
                        data[vIdx + 7] = wetness
                        data[vIdx + 8] = ao
                        data[vIdx + 9] = pigment
                    }
                }
                toeIndex++
            }
        }

        mesh.setIndices(indices)
        mesh.commitVertices()
        return mesh
    }

    /**
     * Precomputes the ultra-high-resolution (128x144) clustered elliptical anterior pelvic patch
     * projected with <0.0001mm precision onto Blossom's exact unified SDF body surface.
     */
    fun buildVulvaPatchCache(): BlossomVulvaPatchCache {
        val cols = VULVA_PATCH_COLS
        val rows = VULVA_PATCH_ROWS
        val cache = BlossomVulvaPatchCache(cols, rows)

        for (r in 0 until rows) {
            val vRaw = (r.toFloat() / (rows - 1)) * 2f - 1f
            // Non-linear vertical concentration over labia, clitoral hood, vestibule & anus
            val v = 0.56f * vRaw + 0.44f * vRaw * vRaw * vRaw
            for (c in 0 until cols) {
                val uRaw = (c.toFloat() / (cols - 1)) * 2f - 1f
                // Strong horizontal concentration across labia majora, sulcus & inner vestibule (~0.5mm spacing!)
                val u = 0.42f * uRaw + 0.58f * uRaw * uRaw * uRaw

                val ex = u * sqrt((1f - 0.5f * v * v).coerceAtLeast(0.0001f))
                val ey = v * sqrt((1f - 0.5f * u * u).coerceAtLeast(0.0001f))
                val edgeR = sqrt(ex * ex + ey * ey).coerceIn(0f, 1f)

                val x = ex * PATCH_RX
                val y = PATCH_CENTER_Y + ey * PATCH_RY
                val sample = sampleBodySurfacePoint(x, y)

                val idx = r * cols + c
                cache.baseX[idx] = x
                cache.baseY[idx] = y
                cache.baseZ[idx] = sample.z
                cache.baseNx[idx] = sample.nx
                cache.baseNy[idx] = sample.ny
                cache.baseNz[idx] = sample.nz
                cache.baseAo[idx] = sample.ao
                cache.basePigment[idx] = sample.pigment
                cache.edgeR[idx] = edgeR
            }
        }
        return cache
    }

    /**
     * Real-time 60 FPS update of Blossom's Ultra-HD deformable vulva patch with zero-seam
     * normal, height, AO, and pigment matching onto the unified SDF body.
     */
    fun updateBlossomVulvaPatch(
        mesh: DynamicGlMesh,
        cache: BlossomVulvaPatchCache,
        detailEvaluator: (x: Float, y: Float) -> VulvaSurfaceSample,
        computeNormals: (FloatArray, Int, Int, Boolean, Boolean) -> Unit
    ) {
        val data = mesh.vertexData
        val cols = cache.cols
        val rows = cache.rows
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                val x = cache.baseX[i]
                val y = cache.baseY[i]
                val edgeR = cache.edgeR[i]

                // Keep 100% full detail across mons (+0.72), vulva, perineum & anus (-0.86), feathering smoothly to 0 by 0.88!
                val vMask = 1f - smoothStep(0.72f, 0.88f, edgeR)
                val d = if (vMask > 0.0005f) detailEvaluator(x, y) else null

                // Gentle overlap lift in [0.84..0.95] keeps the high-res patch strictly in front of the recessed SDF collar,
                // then tucks invisibly beneath the unmodified SDF surface at [0.96..1.00] with matched normals & material!
                val overlapBias = 0.0032f * smoothStep(0.80f, 0.88f, edgeR) * (1f - smoothStep(0.93f, 0.97f, edgeR)) -
                    0.0035f * smoothStep(0.96f, 1.00f, edgeR)

                val dz = (d?.dz ?: 0f) * vMask
                // Displace cleanly along Z
                val pz = cache.baseZ[i] + dz + overlapBias

                val mucosa = ((d?.mucosa ?: 0f) * vMask).coerceIn(0f, 1f)
                val wetness = (0.055f + (d?.wetness ?: 0f) * vMask).coerceIn(0f, 1f)
                val detailAo = 1f - (1f - (d?.ao ?: 1f)) * vMask
                val ao = (cache.baseAo[i] * detailAo).coerceIn(0.06f, 1f)
                val pigment = (cache.basePigment[i] + (d?.pigment ?: 0f) * vMask).coerceIn(0f, 1f)

                val vIdx = i * stride
                data[vIdx] = x
                data[vIdx + 1] = y
                data[vIdx + 2] = pz
                data[vIdx + 6] = mucosa
                data[vIdx + 7] = wetness
                data[vIdx + 8] = ao
                data[vIdx + 9] = pigment
            }
        }

        computeNormals(data, cols, rows, false, false)

        // Exact outer-skirt normal matching to the unified SDF body's analytical gradient normals -> ZERO seam!
        for (i in 0 until cache.count) {
            val edgeR = cache.edgeR[i]
            val normalBlend = smoothStep(0.74f, 0.92f, edgeR)
            if (normalBlend > 0.001f) {
                val vIdx = i * stride
                val mx = data[vIdx + 3] * (1f - normalBlend) + cache.baseNx[i] * normalBlend
                val my = data[vIdx + 4] * (1f - normalBlend) + cache.baseNy[i] * normalBlend
                val mz = data[vIdx + 5] * (1f - normalBlend) + cache.baseNz[i] * normalBlend
                val len = sqrt(mx * mx + my * my + mz * mz)
                if (len > 1e-5f) {
                    data[vIdx + 3] = mx / len
                    data[vIdx + 4] = my / len
                    data[vIdx + 5] = mz / len
                } else {
                    data[vIdx + 3] = cache.baseNx[i]
                    data[vIdx + 4] = cache.baseNy[i]
                    data[vIdx + 5] = cache.baseNz[i]
                }
            }
        }

        mesh.commitVertices()
    }

    // -------------------------------------------------------------------------
    // 60 FPS HIP-SOCKET ARTICULATED LEG-SPREAD SKINNING (BODY + TOES + TOENAILS)
    // -------------------------------------------------------------------------
    const val HIP_PIVOT_AX = 1.12f
    const val HIP_PIVOT_Y = 0.26f
    const val HIP_PIVOT_Z = -0.22f
    const val MAX_LEG_SPREAD_RAD = 0.25f

    class LegSkinCache(
        val bodyVertIndices: IntArray,
        val bodyBasePosNorm: FloatArray,
        val bodyWeights: FloatArray,
        val bodySides: FloatArray,
        val toesBasePosNorm: FloatArray,
        val toesSides: FloatArray,
        val toeNailStartVert: Int,
        val toeNailsBasePosNorm: FloatArray,
        val toeNailsSides: FloatArray
    )

    fun buildLegSkinCache(
        bodyMesh: DynamicGlMesh,
        toesMesh: DynamicGlMesh,
        nailsMesh: DynamicGlMesh,
        toeNailStartVert: Int
    ): LegSkinCache {
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val bData = bodyMesh.vertexData

        val idxBuf = IntArray(bodyMesh.vertexCount)
        val weightBuf = FloatArray(bodyMesh.vertexCount)
        val sideBuf = FloatArray(bodyMesh.vertexCount)
        var count = 0

        // Femur bone vector in +X half-space (Hip -> Knee)
        val fdx = 1.90f - HIP_PIVOT_AX
        val fdy = -2.19f - HIP_PIVOT_Y
        val fdz = 2.01f - HIP_PIVOT_Z
        val fLen2 = fdx * fdx + fdy * fdy + fdz * fdz

        for (i in 0 until bodyMesh.vertexCount) {
            val base = i * stride
            val x = bData[base]
            val y = bData[base + 1]
            val z = bData[base + 2]
            if (y > 0.44f) continue

            val ax = abs(x)
            // Keep forearm, wrist & palm strictly stationary even beside hips!
            if (y > -0.72f && ax > 1.82f && z < -0.35f) continue

            // Keep Ultra-HD vulva patch perimeter 100% locked (zero seam drift!)
            val ovalR = sqrt((x / PATCH_RX).let { it * it } + ((y - PATCH_CENTER_Y) / PATCH_RY).let { it * it })
            if (ovalR <= 1.04f) continue

            val hFem = ((ax - HIP_PIVOT_AX) * fdx + (y - HIP_PIVOT_Y) * fdy + (z - HIP_PIVOT_Z) * fdz) / fLen2
            val patchGuard = smoothStep(1.04f, 1.38f, ovalR)
            val groinYGuard = smoothStep(0.38f, -0.58f, y)
            val femRamp = smoothStep(0.06f, 0.42f, hFem)
            val lowerLimbFull = smoothStep(-1.05f, -1.65f, y)
            val w = max(femRamp * groinYGuard, lowerLimbFull) * patchGuard
            if (w > 0.001f) {
                idxBuf[count] = i
                weightBuf[count] = w.coerceIn(0f, 1f)
                sideBuf[count] = if (x >= 0f) 1f else -1f
                count++
            }
        }

        val bodyIndices = idxBuf.copyOf(count)
        val bodyWeights = weightBuf.copyOf(count)
        val bodySides = sideBuf.copyOf(count)
        val bodyBase = FloatArray(count * 6)
        for (k in 0 until count) {
            val src = bodyIndices[k] * stride
            val dst = k * 6
            bodyBase[dst] = bData[src]; bodyBase[dst + 1] = bData[src + 1]; bodyBase[dst + 2] = bData[src + 2]
            bodyBase[dst + 3] = bData[src + 3]; bodyBase[dst + 4] = bData[src + 4]; bodyBase[dst + 5] = bData[src + 5]
        }

        val tData = toesMesh.vertexData
        val toesBase = FloatArray(toesMesh.vertexCount * 6)
        val toesSides = FloatArray(toesMesh.vertexCount)
        for (i in 0 until toesMesh.vertexCount) {
            val src = i * stride
            val dst = i * 6
            toesBase[dst] = tData[src]; toesBase[dst + 1] = tData[src + 1]; toesBase[dst + 2] = tData[src + 2]
            toesBase[dst + 3] = tData[src + 3]; toesBase[dst + 4] = tData[src + 4]; toesBase[dst + 5] = tData[src + 5]
            toesSides[i] = if (tData[src] >= 0f) 1f else -1f
        }

        val nData = nailsMesh.vertexData
        val toeNailCount = (nailsMesh.vertexCount - toeNailStartVert).coerceAtLeast(0)
        val toeNailsBase = FloatArray(toeNailCount * 6)
        val toeNailsSides = FloatArray(toeNailCount)
        for (k in 0 until toeNailCount) {
            val src = (toeNailStartVert + k) * stride
            val dst = k * 6
            toeNailsBase[dst] = nData[src]; toeNailsBase[dst + 1] = nData[src + 1]; toeNailsBase[dst + 2] = nData[src + 2]
            toeNailsBase[dst + 3] = nData[src + 3]; toeNailsBase[dst + 4] = nData[src + 4]; toeNailsBase[dst + 5] = nData[src + 5]
            toeNailsSides[k] = if (nData[src] >= 0f) 1f else -1f
        }

        return LegSkinCache(
            bodyVertIndices = bodyIndices,
            bodyBasePosNorm = bodyBase,
            bodyWeights = bodyWeights,
            bodySides = bodySides,
            toesBasePosNorm = toesBase,
            toesSides = toesSides,
            toeNailStartVert = toeNailStartVert,
            toeNailsBasePosNorm = toeNailsBase,
            toeNailsSides = toeNailsSides
        )
    }

    /**
     * Evaluates the exact world-space position of any leg point after interactive hip-socket abduction.
     */
    fun transformLegPoint(x: Float, y: Float, z: Float, side: Float, spread: Float, weight: Float = 1f): FloatArray {
        val clampedSpread = spread.coerceIn(-0.35f, 1.0f)
        val angle = side * clampedSpread * MAX_LEG_SPREAD_RAD * weight
        val cosA = cos(angle)
        val sinA = sin(angle)
        val pivotX = side * HIP_PIVOT_AX
        val rx = x - pivotX
        val ry = y - HIP_PIVOT_Y
        val nx = pivotX + rx * cosA - ry * sinA
        val ny = HIP_PIVOT_Y + rx * sinA + ry * cosA
        val kneeDrop = -0.14f * clampedSpread * weight * smoothStep(-0.55f, 1.50f, z)
        return floatArrayOf(nx, ny, z + kneeDrop)
    }

    fun applyLegSpread(
        bodyMesh: DynamicGlMesh,
        toesMesh: DynamicGlMesh,
        nailsMesh: DynamicGlMesh,
        cache: LegSkinCache,
        leftLegSpread: Float,
        rightLegSpread: Float
    ) {
        val stride = DynamicGlMesh.FLOATS_PER_VERTEX
        val leftS = leftLegSpread.coerceIn(-0.35f, 1.0f)
        val rightS = rightLegSpread.coerceIn(-0.35f, 1.0f)

        val bData = bodyMesh.vertexData
        val bIdx = cache.bodyVertIndices
        val bBase = cache.bodyBasePosNorm
        val bWeights = cache.bodyWeights
        val bSides = cache.bodySides
        for (k in bIdx.indices) {
            val side = bSides[k]
            val spread = if (side < 0f) leftS else rightS
            val w = bWeights[k]
            val angle = side * spread * MAX_LEG_SPREAD_RAD * w
            val cosA = cos(angle)
            val sinA = sin(angle)
            val pivotX = side * HIP_PIVOT_AX

            val src = k * 6
            val x = bBase[src]; val y = bBase[src + 1]; val z = bBase[src + 2]
            val nx = bBase[src + 3]; val ny = bBase[src + 4]; val nz = bBase[src + 5]

            val rx = x - pivotX
            val ry = y - HIP_PIVOT_Y
            val kneeDrop = -0.14f * spread * w * smoothStep(-0.55f, 1.50f, z)

            val dst = bIdx[k] * stride
            bData[dst] = pivotX + rx * cosA - ry * sinA
            bData[dst + 1] = HIP_PIVOT_Y + rx * sinA + ry * cosA
            bData[dst + 2] = z + kneeDrop
            bData[dst + 3] = nx * cosA - ny * sinA
            bData[dst + 4] = nx * sinA + ny * cosA
            bData[dst + 5] = nz
        }
        bodyMesh.commitVertices()

        val tData = toesMesh.vertexData
        val tBase = cache.toesBasePosNorm
        val tSides = cache.toesSides
        for (i in 0 until toesMesh.vertexCount) {
            val side = tSides[i]
            val spread = if (side < 0f) leftS else rightS
            val angle = side * spread * MAX_LEG_SPREAD_RAD
            val cosA = cos(angle)
            val sinA = sin(angle)
            val pivotX = side * HIP_PIVOT_AX

            val src = i * 6
            val x = tBase[src]; val y = tBase[src + 1]; val z = tBase[src + 2]
            val nx = tBase[src + 3]; val ny = tBase[src + 4]; val nz = tBase[src + 5]

            val rx = x - pivotX
            val ry = y - HIP_PIVOT_Y

            val dst = i * stride
            tData[dst] = pivotX + rx * cosA - ry * sinA
            tData[dst + 1] = HIP_PIVOT_Y + rx * sinA + ry * cosA
            tData[dst + 2] = z
            tData[dst + 3] = nx * cosA - ny * sinA
            tData[dst + 4] = nx * sinA + ny * cosA
            tData[dst + 5] = nz
        }
        toesMesh.commitVertices()

        val nData = nailsMesh.vertexData
        val nBase = cache.toeNailsBasePosNorm
        val nSides = cache.toeNailsSides
        val nStart = cache.toeNailStartVert
        for (k in nSides.indices) {
            val side = nSides[k]
            val spread = if (side < 0f) leftS else rightS
            val angle = side * spread * MAX_LEG_SPREAD_RAD
            val cosA = cos(angle)
            val sinA = sin(angle)
            val pivotX = side * HIP_PIVOT_AX

            val src = k * 6
            val x = nBase[src]; val y = nBase[src + 1]; val z = nBase[src + 2]
            val nx = nBase[src + 3]; val ny = nBase[src + 4]; val nz = nBase[src + 5]

            val rx = x - pivotX
            val ry = y - HIP_PIVOT_Y

            val dst = (nStart + k) * stride
            nData[dst] = pivotX + rx * cosA - ry * sinA
            nData[dst + 1] = HIP_PIVOT_Y + rx * sinA + ry * cosA
            nData[dst + 2] = z
            nData[dst + 3] = nx * cosA - ny * sinA
            nData[dst + 4] = nx * sinA + ny * cosA
            nData[dst + 5] = nz
        }
        nailsMesh.commitVertices()
    }

    // -------------------------------------------------------------------------
    // SDF PRIMITIVES & FAST PRIMITIVE COLLECTIONS
    // -------------------------------------------------------------------------
    private fun smin(a: Float, b: Float, k: Float): Float {
        val h = (k - abs(a - b)).coerceAtLeast(0f) / k
        return min(a, b) - h * h * h * k * (1f / 6f)
    }

    private fun smax(a: Float, b: Float, k: Float): Float = -smin(-a, -b, k)

    private fun sdEllipse2D(px: Float, py: Float, rx: Float, ry: Float): Float {
        val k0x = px / rx
        val k0y = py / ry
        val k0 = sqrt(k0x * k0x + k0y * k0y)
        if (k0 < 1e-6f) return -min(rx, ry)
        val k1x = px / (rx * rx)
        val k1y = py / (ry * ry)
        val k1 = sqrt(k1x * k1x + k1y * k1y)
        return k0 * (k0 - 1f) / k1
    }

    private fun sdEllipsoid(px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float): Float {
        val k0x = px / rx
        val k0y = py / ry
        val k0z = pz / rz
        val k0 = sqrt(k0x * k0x + k0y * k0y + k0z * k0z)
        if (k0 < 1e-6f) return -min(rx, min(ry, rz))
        val k1x = px / (rx * rx)
        val k1y = py / (ry * ry)
        val k1z = pz / (rz * rz)
        val k1 = sqrt(k1x * k1x + k1y * k1y + k1z * k1z)
        return k0 * (k0 - 1f) / k1
    }

    private fun sdCapsule(
        px: Float, py: Float, pz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        rA: Float, rB: Float
    ): Float {
        val pax = px - ax; val pay = py - ay; val paz = pz - az
        val bax = bx - ax; val bay = by - ay; val baz = bz - az
        val ba2 = bax * bax + bay * bay + baz * baz
        val h = ((pax * bax + pay * bay + paz * baz) / ba2).coerceIn(0f, 1f)
        val dx = pax - bax * h
        val dy = pay - bay * h
        val dz = paz - baz * h
        val r = rA + (rB - rA) * h
        return sqrt(dx * dx + dy * dy + dz * dz) - r
    }

    private fun sqDist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun smoothBell(x: Float, start: Float, end: Float, feather: Float): Float {
        return smoothStep(start - feather, start + feather, x) *
            (1f - smoothStep(end - feather, end + feather, x))
    }

    private class FloatArrayList(initialCapacity: Int) {
        var data = FloatArray(initialCapacity)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toTrimmedArray(): FloatArray = data.copyOf(size)
    }

    private class IntArrayList(initialCapacity: Int) {
        var data = IntArray(initialCapacity)
        var size = 0
        fun add4(a: Int, b: Int, c: Int, d: Int) {
            if (size + 4 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a
            data[size++] = b
            data[size++] = c
            data[size++] = d
        }
    }

    private class ShortArrayList(initialCapacity: Int) {
        var data = ShortArray(initialCapacity)
        var size = 0
        fun add6(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
            if (size + 6 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a.toShort()
            data[size++] = b.toShort()
            data[size++] = c.toShort()
            data[size++] = d.toShort()
            data[size++] = e.toShort()
            data[size++] = f.toShort()
        }
        fun toTrimmedArray(): ShortArray = data.copyOf(size)
    }
}
