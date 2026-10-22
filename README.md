# Regulador de Foco (Android)

Um "óculos" para a tela do celular, para quem enxerga com dificuldade: sem óculos à mão, com a vista cansada ou antes de dormir. Um teste rápido mede como cada olho enxerga, e o app aumenta letras e tela inteira, em qualquer app, na medida certa para a pessoa.

O app não corrige a visão nem substitui a consulta com oftalmologista. O embaçado acontece dentro do olho, e nenhum programa muda o foco da luz. O que dá para fazer é deixar tudo grande o bastante para não forçar a vista, e é isso que o app faz.

## O que o app faz

- **Teste da visão de cada olho**, com a letra **E** girando, como no consultório. A pessoa tapa um olho e testa o outro, depois troca, e no fim testa com os dois juntos. Tudo na distância em que ela segura o celular. O app mede o menor "E" (em mm na tela) em que ela ainda acerta para onde ele aponta.
- **Escolha do olho**: a tela é uma só e os dois olhos veem a mesma imagem. Por isso o ajuste usa o resultado dos dois olhos juntos (recomendado), ou só o do esquerdo ou só o do direito. Se um olho enxerga bem pior que o outro, o app avisa para procurar um oftalmologista.
- **Ajuste calibrado**: o texto comum fica com o dobro do menor tamanho que a pessoa distingue, que é a folga para ler sem esforço. Primeiro aumentam as **letras do sistema** (até 200%). Se ainda não for suficiente, entra o **zoom na tela inteira** (até 5×).
- **Modos**:
  - **Sem óculos**: o ajuste do teste.
  - **Antes de dormir**: 15% maior e com um filtro âmbar que deixa a tela mais quente e mais fraca.
  - **Normal**: devolve tudo como estava.
- **Ajuste fino** de 70% a 150% do recomendado.
- **O zoom segue você**: ao tocar num campo ou digitar, a área ampliada vai para lá. Para mover a tela ampliada, arraste com dois dedos. Se não mover, ligue também o atalho de Ampliação do Android.
- **Botão rápido "Óculos de tela"** na barra de notificações: um toque liga o último modo, outro desliga.
- Durante o teste, o zoom e o filtro saem sozinhos, para o "E" aparecer no tamanho real.

## Instalar no celular

1. Abra a página **Releases** do repositório no celular e baixe `app-debug.apk` da versão "Última versão".
2. Permita "instalar apps desconhecidos" para o navegador (só na primeira vez) e instale.
3. No app, toque em **Permitir mudar as letras** e ligue a opção.
4. Toque em **Abrir Acessibilidade**, procure **Apps instalados → Regulador de Foco** e ligue. Se o Android disser que é uma "configuração restrita": abra **Informações do app**, toque nos 3 pontinhos (⋮) e em **Permitir configurações restritas**. Depois tente de novo.

Cada push na `main` gera um APK novo automaticamente.

## Como funciona por dentro

- `VisionMath.kt`: tamanhos do "E" (0,4 a 12,6 mm, em passos de 0,1 logMAR), a escada do teste (2 acertos em até 3 tentativas para passar de tamanho) e a conta de letras + zoom. Testado em `VisionMathTest.kt`.
- `VisionTestActivity.kt`: o teste, olho por olho.
- `Regulator.kt`: liga os modos e muda o tamanho das letras do sistema (`Settings.System.FONT_SCALE`, com a permissão "alterar configurações do sistema").
- `FocusService.kt`: serviço de acessibilidade que aplica o zoom na tela toda (`MagnificationController`), centraliza onde há foco ou digitação e desenha o filtro da noite.
- `RegulatorTileService.kt`: o botão na barra de notificações.

## Próximos passos

- **Pré-nitidez**: deixar a imagem "pré-afiada" de acordo com o embaçado medido, para o olho sem óculos enxergar mais nítido. É experimental: exige capturar a tela continuamente.
- Teste de contraste e de astigmatismo.
- Tamanho da tela (densidade) além das letras, para quem libera a permissão pelo computador.
