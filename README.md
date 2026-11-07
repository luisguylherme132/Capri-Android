# Capri Mobile (Android)

Port pessoal do [capri-desktop](https://github.com/lachydotmcg/capri-desktop) para Android.
Projeto fã não oficial. Licença: PolyForm Noncommercial 1.0.0 (veja `LICENSE.md`).

Required Notice: Copyright 2026 Lachy (https://github.com/lachydotmcg)
Crédito obrigatório: projeto Capri (https://github.com/lachydotmcg/capri-desktop). Arte e animações por Capri's artist.

## Como rodar

1. Abra a pasta no **Android Studio** (Koala ou mais novo). Se ele pedir pra configurar o Gradle, aceite.
2. Sync, conecte o celular (ou emulador) e rode o módulo `app`.
3. No app: preencha URL da API, chave e modelo (qualquer API compatível com OpenAI: OpenAI, OpenRouter, Groq, Ollama, LM Studio).
4. Toque em "1. Allow display over other apps", depois em "2. Allow microphone and notifications".
5. Toque em "Call Capri".

## O que ela faz

- Fica flutuando por cima de qualquer app (overlay + serviço em primeiro plano)
- **Rosto**: o retrato `face.png` com olhos que olham em volta, piscar, sorriso e boca que mexe conforme a letra falada (porte do `GameHud.cs`). Aparece no balão e no topo do app; toque nele pra ela sorrir
- Toque na Capri: chat (texto ou botão MIC) · Arrastar: ela anda · Segurar: dança
- Dorme depois de 90 s parada, acorda ao toque; pisca e glitcha sozinha
- **Voz** (em Voice no app, com botão Test voice):
  - Android (offline)
  - Servidor compatível com OpenAI (`POST {url}/audio/speech`): OpenAI ou Kokoro-FastAPI no seu PC (ex.: `http://IP-DO-PC:8880/v1`, modelo `kokoro`, voz `af_bella`)
  - ElevenLabs (chave + voice id)
  - Se a nuvem falhar, ela cai pra voz do Android
- **Personalidade original** (do `Brain.cs`, com as linhas de segurança), editável como o `personality.md`
- **Lembretes**: peça "me lembra em 10 minutos de..." e ela avisa por balão, voz e notificação (só enquanto ela estiver na tela)
- Tamanho ajustável, memória das últimas 24 mensagens (só no aparelho)

## O que ficou de fora

Sentar nas janelas de outros apps, controlar cursor/teclado, call mode do Discord, Husk Mode,
jogos, OCR/visão de tela, "Hey Capri", sistema de mods/skins, música no mini player e as poses
perch/laydown/dangle.

## Notas

- A chave da API fica em SharedPreferences privado do app (sem criptografia extra).
- `usesCleartextTraffic` está ligado para permitir servidores locais por http (Ollama etc.).
- Sprites copiados de `assets/sprites` do projeto original (tiras de 240x384 por quadro).
