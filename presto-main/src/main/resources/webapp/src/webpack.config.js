module.exports = {
    entry: {
        'index': __dirname +'/index.jsx',
        'query': __dirname +'/query.jsx',
        'plan': __dirname +'/plan.jsx',
        'embedded_plan': __dirname +'/embedded_plan.jsx',
        'stage': __dirname +'/stage.jsx',
        'worker': __dirname +'/worker.jsx',
        'hetuqueryeditor': __dirname +'/hetuqueryeditor.jsx',
        'overview': __dirname +'/overview.jsx',
        'nodes': __dirname +'/nodes.jsx',
        'headerfooter': __dirname +'/HeaderFooter.jsx',
        'auditlog': __dirname + '/auditlog.jsx',
        'querymonitor': __dirname + '/querymonitor.jsx',
    },
    mode: "development",
    module: {
        rules: [
            {
                test: /\.(js|jsx)$/,
                exclude: /node_modules/,
                use: ['babel-loader']
            }
        ]
    },
    resolve: {
        extensions: ['*', '.js', '.jsx']
    },
    output: {
        path: __dirname + '/../dist',
        filename: '[name].js'
    }
};
