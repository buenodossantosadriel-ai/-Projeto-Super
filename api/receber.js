export default function handler(req, res) {
  res.status(200).json({
    sucesso: true,
    projeto: "Projeto Super",
    mensagem: "API funcionando corretamente!"
  });
}
