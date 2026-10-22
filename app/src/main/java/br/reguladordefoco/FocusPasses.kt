package br.reguladordefoco

/**
 * Os shaders da correção de foco (AGSL), sem nada de Android, para conferir no PC.
 *
 * Método: Landweber projetado. Quer-se uma imagem p que, borrada pelo olho (disco de raio R),
 * fique o mais perto possível do texto original. A cada iteração:
 *   r = c - borrão(p)                  (o que ainda falta)
 *   p = limita_0_a_1(p + BETA * borrão(r))
 * O "limita" respeita o preto e o branco da tela. Para sobrar espaço para a correção, o
 * contraste do original é reduzido antes (c = 0,5 + k·(y − 0,5)): esse k é o foco fino.
 * Trabalha no brilho (y); a cor volta no fim somando a mudança de brilho.
 *
 * Estado entre passadas: canal R = p; canal G = resíduo, guardado como 0,5 + r·RESIDUAL_GAIN.
 */
object FocusPasses {
    const val ITERATIONS = 8
    const val BETA = 1.8
    const val RESIDUAL_GAIN = 2.0

    private const val HEADER = """
        uniform shader content;
        uniform shader orig;
        uniform float2 size;
        uniform float radius;
        uniform float k;

        half lum(half3 c) { return dot(c, half3(0.2126, 0.7152, 0.0722)); }

        // Alvo: o brilho do original com o contraste reduzido.
        half target(float2 p) { return 0.5 + half(k) * (lum(orig.eval(p).rgb) - 0.5); }

        float2 inside(float2 q) { return clamp(q, float2(0.5), size - 0.5); }
    """

    /** Média de um canal do estado num disco de raio [radius]: 4 anéis (6, 12, 18, 24) + centro. */
    private fun diskMean(channel: String) = buildString {
        append("half disk_$channel(float2 p) {\n")
        append("    half sum = content.eval(p).$channel;\n")
        for ((ring, taps) in listOf(1 to 6, 2 to 12, 3 to 18, 4 to 24)) {
            append("    for (int i = 0; i < $taps; i++) {\n")
            append("        float a = 6.2831853 * (float(i) + 0.5) / $taps.0;\n")
            append("        sum += content.eval(inside(p + radius * $ring.0 / 4.0 * float2(cos(a), sin(a)))).$channel;\n")
            append("    }\n")
        }
        append("    return sum / 61.0;\n")
        append("}\n")
    }

    /** Começo: p = alvo. */
    val INIT = HEADER + """
        half4 main(float2 p) {
            return half4(target(p), 0.5, 0.0, 1.0);
        }
    """

    /** r = alvo − borrão(p), guardado no canal G. */
    val RESIDUAL = HEADER + diskMean("r") + """
        half4 main(float2 p) {
            half r = target(p) - disk_r(p);
            return half4(content.eval(p).r, clamp(0.5 + r * $RESIDUAL_GAIN, 0.0, 1.0), 0.0, 1.0);
        }
    """

    /** p = limita(p + BETA · borrão(r)). */
    val UPDATE = HEADER + diskMean("g") + """
        half4 main(float2 p) {
            half r = (disk_g(p) - 0.5) / $RESIDUAL_GAIN;
            return half4(clamp(content.eval(p).r + $BETA * r, 0.0, 1.0), 0.5, 0.0, 1.0);
        }
    """

    /** Fim: a cor do original (com o mesmo contraste) mais a mudança de brilho calculada. */
    val FINAL = HEADER + """
        half4 main(float2 p) {
            half3 o = orig.eval(p).rgb;
            half3 c = 0.5 + half(k) * (o - 0.5);
            half change = content.eval(p).r - target(p);
            return half4(clamp(c + change, 0.0, 1.0), 1.0);
        }
    """

    /** A ordem das passadas: começo, ITERATIONS vezes (resíduo, atualização), fim. */
    val ORDER: List<String> = listOf(INIT) + List(ITERATIONS) { listOf(RESIDUAL, UPDATE) }.flatten() + FINAL
}
