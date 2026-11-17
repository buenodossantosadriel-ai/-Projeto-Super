const statusEl = document.querySelector("#status");
const resultadoEl = document.querySelector("#resultado");
const testarBtn = document.querySelector("#testar");

async function testarAplicacao() {
  statusEl.textContent = "Conectando à API...";
  resultadoEl.textContent = "";

  try {
    const resposta = await fetch("/api/receber");
    const dados = await resposta.json();

    if (!resposta.ok) {
      throw new Error(dados.erro || "Não foi possível acessar a API.");
    }

    statusEl.textContent = "Aplicação funcionando!";
    resultadoEl.textContent = JSON.stringify(dados, null, 2);
  } catch (erro) {
    statusEl.textContent = "A API ainda não respondeu.";
    resultadoEl.textContent = erro.message;
  }
}

testarBtn.addEventListener("click", testarAplicacao);
