const app = require('./backend/index.js');

if (require.main === module) {
    const port = Number(process.env.PORT) || 3000;
    app.listen(port, () => {
        console.log(`InfoCaller backend listening on port ${port}`);
    });
}

module.exports = app;
